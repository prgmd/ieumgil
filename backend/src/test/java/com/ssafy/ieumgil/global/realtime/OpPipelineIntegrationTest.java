package com.ssafy.ieumgil.global.realtime;

import com.ssafy.ieumgil.domain.activitylog.entity.ActivityLog;
import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.block.service.BlockCommandService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * op 파이프라인 규약 — seq 단조증가, 저널 재전송, 스테일 시 op 미발행, 유령 UPDATE 회귀 감시.
 */
class OpPipelineIntegrationTest extends IntegrationTestSupport {

    @Autowired
    BlockCommandService blockCommandService;
    @Autowired
    BlockRepository blockRepository;
    @Autowired
    ActivityLogRepository activityLogRepository;
    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    StringRedisTemplate redisTemplate;

    private long createBlock(User user, Project project, String name) {
        return blockCommandService.createBlock(user.getId(), project.getId(), null,
                new BlockReqDTO.Create(BlockCategory.ETC, name, 1, null, null, null,
                        null, null, null, null, null, null, null, null, null,
                        BlockSource.MANUAL, null, null))
                .seq();
    }

    @Test
    @DisplayName("같은 프로젝트의 op에는 단조증가 seq가 붙고, afterSeq 재전송이 유실분을 그대로 돌려준다")
    void seqIsMonotonicAndReplayable() {
        User user = seedUser();
        Project project = seedProject(user);

        long s1 = createBlock(user, project, "블록1");
        long s2 = createBlock(user, project, "블록2");
        long s3 = createBlock(user, project, "블록3");

        assertThat(s2).isEqualTo(s1 + 1);
        assertThat(s3).isEqualTo(s2 + 1);
        assertThat(activityLogRepository.findLastSeq(project.getId())).isEqualTo(s3);

        // "s1 이후 전부 줘" — 스냅샷 이후 유실분 복구 경로. 저장된 전문이 그대로 나와야 한다
        List<Map<String, Object>> ops = activityLogRepository.findOpsAfter(project.getId(), s1, Pageable.unpaged());
        assertThat(ops).hasSize(2);
        assertThat(((Number) ops.get(0).get("seq")).longValue()).isEqualTo(s2);
        assertThat(((Number) ops.get(1).get("seq")).longValue()).isEqualTo(s3);
        assertThat(ops).allSatisfy(op -> assertThat(op.get("type")).isEqualTo("BLOCK_CREATED"));
    }

    @Test
    @DisplayName("op가 하나도 없는 프로젝트의 lastSeq는 0 — 클라이언트가 afterSeq=0부터 시작할 수 있다")
    void lastSeqDefaultsToZero() {
        User user = seedUser();
        Project project = seedProject(user);

        assertThat(activityLogRepository.findLastSeq(project.getId())).isZero();
    }

    @Test
    @DisplayName("모든 필드가 스테일이면 op를 발행하지 않는다 — seq 낭비와 의미 없는 브로드캐스트 방지")
    void allStaleProducesNoOp() {
        User user = seedUser();
        Project project = seedProject(user);
        Map<String, String> future = new HashMap<>();
        future.put("budget", Instant.now().plusSeconds(3600).toString());
        Block block = blockRepository.save(Block.builder()
                .name("스테일 대상")
                .category(BlockCategory.ETC)
                .orderKey("a0")
                .source(BlockSource.MANUAL)
                .project(project)
                .author(user)
                .fieldUpdatedAt(future)
                .build());
        long before = activityLogRepository.findLastSeq(project.getId());

        var result = blockCommandService.updateFields(user.getId(), block.getId(), null,
                new BlockReqDTO.UpdateFields(List.of(new BlockReqDTO.FieldChange("budget", 5000))));

        assertThat(result.applied()).containsEntry("budget", false);
        assertThat(activityLogRepository.findLastSeq(project.getId())).isEqualTo(before);
    }

    @Test
    @DisplayName("Redis 카운터가 유실돼도 seq는 DB 기준으로 이어진다 — 지연 리시드")
    void seqReseedsFromDbAfterRedisLoss() {
        User user = seedUser();
        Project project = seedProject(user);
        createBlock(user, project, "블록1");
        long s2 = createBlock(user, project, "블록2");

        // Redis 재기동/flush 시뮬레이션 — 카운터 키만 사라지고 저널(DB)은 남는 상황
        redisTemplate.delete("project:" + project.getId() + ":seq");

        long s3 = createBlock(user, project, "블록3");

        // INCR이 1부터 다시 시작하면 UNIQUE(project_id, seq) 충돌로 저장이 죽는다 —
        // SeqGenerator가 DB MAX(seq) 기준으로 이어붙이는지가 이 테스트의 핵심
        assertThat(s3).isEqualTo(s2 + 1);
        assertThat(activityLogRepository.findLastSeq(project.getId())).isEqualTo(s3);
    }

    @Test
    @DisplayName("유령 UPDATE 회귀 감시 — 이동 1회는 block UPDATE 1 + activity_log INSERT 1이 전부다")
    void moveDoesNotGhostUpdateJournal() {
        User user = seedUser();
        Project project = seedProject(user);
        long blockId = blockCommandService.createBlock(user.getId(), project.getId(), null,
                new BlockReqDTO.Create(BlockCategory.ETC, "이동 대상", 1, "a0", null, null,
                        null, null, null, null, null, null, null, null, null,
                        BlockSource.MANUAL, null, null))
                .blockId();

        // 배경: JSONB 스냅샷 딥카피가 Long→Integer로 되살아나 INSERT 직후 매번
        // 불필요한 UPDATE가 나갔다. ActivityLog의 @Immutable이 이를 봉인한다 —
        // 누군가 @Immutable을 제거하면 이 테스트가 깨진다.
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        blockCommandService.move(user.getId(), blockId, null, new BlockReqDTO.Move(2, "b0"));

        assertThat(stats.getEntityStatistics(ActivityLog.class.getName()).getUpdateCount()).isZero();
        assertThat(stats.getEntityStatistics(ActivityLog.class.getName()).getInsertCount()).isEqualTo(1);
        assertThat(stats.getEntityStatistics(Block.class.getName()).getUpdateCount()).isEqualTo(1);
    }
}
