package com.ssafy.ieumgil.global.realtime;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.websocket.StompBroadcaster;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 브로드캐스트는 반드시 커밋 이후(AFTER_COMMIT)에만 나간다 — 롤백된 변경이
 * 다른 사용자 화면에 "유령 상태"로 남는 것을 구조로 막는 규약의 회귀 테스트.
 * OpBroadcastListener의 phase를 AFTER_COMMIT이 아닌 값으로 바꾸면 깨진다.
 */
class AfterCommitBroadcastIntegrationTest extends IntegrationTestSupport {

    @MockitoSpyBean
    StompBroadcaster stompBroadcaster;

    @Autowired
    TxProbe txProbe;
    @Autowired
    ActivityLogRepository activityLogRepository;

    @Test
    @DisplayName("트랜잭션이 커밋되면 op가 브로드캐스트된다")
    void broadcastAfterCommit() {
        User user = seedUser();
        Project project = seedProject(user);

        long seq = txProbe.publish(project.getId(), user.getId(), false);

        verify(stompBroadcaster, timeout(1000)).send(eq(project.getId()), anyMap());
        // seq 절대값은 단언하지 않는다 — Redis 카운터는 테스트 컨텍스트 간에 공유될 수 있고,
        // 규약이 보장하는 것은 "저널의 마지막 seq = 방금 발행한 seq"까지다
        assertThat(activityLogRepository.findLastSeq(project.getId())).isEqualTo(seq);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 브로드캐스트도, 저널도 남지 않는다")
    void nothingLeaksOnRollback() {
        User user = seedUser();
        Project project = seedProject(user);

        assertThatThrownBy(() -> txProbe.publish(project.getId(), user.getId(), true))
                .isInstanceOf(IllegalStateException.class);

        // 커밋 전에 쐈다면 여기서 send가 잡힌다 — 유령 상태가 남의 화면에 도착했다는 뜻
        verify(stompBroadcaster, never()).send(eq(project.getId()), anyMap());
        assertThat(activityLogRepository.findLastSeq(project.getId())).isZero();
    }

    /** 변경 API를 흉내 내는 최소 프로브 — publish 후 커밋 또는 강제 롤백 */
    static class TxProbe {
        private final OpPublisher opPublisher;

        TxProbe(OpPublisher opPublisher) {
            this.opPublisher = opPublisher;
        }

        @Transactional
        public long publish(Long projectId, Long actorId, boolean forceRollback) {
            long seq = opPublisher.publish(projectId, actorId, null, "TEST_OP", Map.of("probe", true));
            if (forceRollback) {
                throw new IllegalStateException("강제 롤백");
            }
            return seq;
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        TxProbe txProbe(OpPublisher opPublisher) {
            return new TxProbe(opPublisher);
        }
    }
}
