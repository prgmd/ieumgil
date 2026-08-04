package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.realtime.OpPublisher;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스냅샷의 lastSeq-blocks 조회 순서 회귀 테스트.
 *
 * READ COMMITTED에서는 쿼리마다 스냅샷이 따로라, blocks를 lastSeq보다 먼저 읽으면
 * 두 쿼리 사이에 커밋된 op가 "lastSeq에는 있고 blocks에는 없는" 상태로 응답에 실린다 —
 * 클라이언트는 그 op의 브로드캐스트를 seq ≤ lastSeq로 스킵해서 블록이 새로고침 전까지
 * 안 보인다. 그래서 getSnapshot은 반드시 lastSeq를 먼저 읽어야 한다.
 *
 * 검증 방법: 두 조회(findLastSeq/findChain) 중 먼저 불리는 쪽의 직후에 새 블록+op 커밋을
 * 끼워 넣는다. 순서가 올바르면(lastSeq 먼저) 끼어든 op는 lastSeq보다 커서 클라이언트가
 * 정상 적용하고, 순서가 뒤집히면(blocks 먼저) blocks에 없는 블록의 seq까지 lastSeq가
 * 광고해 이 테스트가 깨진다.
 *
 * JPA 리포지토리는 인터페이스 프록시라 Mockito 스파이의 callRealMethod가 동작하지 않는다
 * ("Cannot call abstract real method"). 그래서 BeanPostProcessor로 프록시를 한 겹 더 감싸
 * 조회 직후 훅(QueryTap)을 끼운다.
 */
class ProjectSnapshotOrderingIntegrationTest extends IntegrationTestSupport {

    /** 감시 대상 조회 — 이 중 "먼저" 실행된 쪽 직후에 훅이 정확히 한 번 발화한다 */
    private static final Set<String> TAPPED_QUERIES = Set.of("findLastSeq", "findChain");

    @Autowired
    ProjectQueryService projectQueryService;
    @Autowired
    SnapshotRaceProbe probe;
    @Autowired
    QueryTap queryTap;

    @AfterEach
    void disarmTap() {
        queryTap.disarm();
    }

    @Test
    @DisplayName("스냅샷 조회 도중 끼어든 op는 lastSeq 이후로 밀린다 — lastSeq가 미포함 블록의 seq를 광고하지 않는다")
    void interleavedOpNeverCoveredByStaleLastSeq() throws Exception {
        User user = seedUser();
        Project project = seedProject(user);

        AtomicReference<SnapshotRaceProbe.Injected> injected = new AtomicReference<>();
        AtomicReference<Throwable> injectionFailure = new AtomicReference<>();

        // 첫 조회 직후, 별도 스레드(=별도 트랜잭션)로 블록+op를 커밋하고 끝나기를 기다린다
        queryTap.arm(() -> {
            Thread committer = new Thread(() -> {
                try {
                    injected.set(probe.createBlockWithOp(project.getId(), user.getId()));
                } catch (Throwable t) {
                    injectionFailure.set(t);
                }
            });
            committer.start();
            try {
                committer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        ProjectResDTO.Snapshot snapshot = projectQueryService.getSnapshot(user.getId(), project.getId());

        assertThat(injectionFailure.get()).isNull();
        assertThat(injected.get()).isNotNull();

        // 핵심 불변식: 끼어든 op의 seq는 lastSeq보다 커야 한다. 같거나 작으면 클라이언트가
        // 그 op의 브로드캐스트를 스킵하는데 blocks에는 해당 블록이 없어 영구 유실이다.
        assertThat(injected.get().seq()).isGreaterThan(snapshot.lastSeq());

        // lastSeq를 먼저 읽는 현재 순서에서는 blocks가 나중 스냅샷이라 끼어든 블록도 담긴다.
        // op가 전부 멱등이라 이 "미리 반영"은 무해하다 — 반대 방향(광고했는데 미포함)만이 유실이다.
        assertThat(snapshot.blocks())
                .anyMatch(item -> item.blockId().equals(injected.get().blockId()));
    }

    /** 감시 조회 직후에 한 번만 발화하는 훅. 테스트가 arm()으로 장전한다 */
    static class QueryTap {
        private final AtomicReference<Runnable> hook = new AtomicReference<>();

        void arm(Runnable afterFirstQuery) {
            hook.set(afterFirstQuery);
        }

        void disarm() {
            hook.set(null);
        }

        void fireOnce() {
            Runnable r = hook.getAndSet(null);
            if (r != null) {
                r.run();
            }
        }
    }

    /** 스냅샷 조회 도중 끼어드는 동시 편집을 흉내 내는 프로브 — 자기 트랜잭션에서 블록+op를 커밋한다 */
    static class SnapshotRaceProbe {
        private final BlockRepository blockRepository;
        private final ProjectRepository projectRepository;
        private final UserRepository userRepository;
        private final OpPublisher opPublisher;

        SnapshotRaceProbe(BlockRepository blockRepository, ProjectRepository projectRepository,
                          UserRepository userRepository, OpPublisher opPublisher) {
            this.blockRepository = blockRepository;
            this.projectRepository = projectRepository;
            this.userRepository = userRepository;
            this.opPublisher = opPublisher;
        }

        record Injected(Long blockId, long seq) {
        }

        @Transactional
        public Injected createBlockWithOp(Long projectId, Long authorId) {
            Block block = blockRepository.save(Block.builder()
                    .name("경합 블록")
                    .category(BlockCategory.ETC)
                    .orderKey("zz")
                    .source(BlockSource.MANUAL)
                    .dayNo(1)
                    .project(projectRepository.getReferenceById(projectId))
                    .author(userRepository.getReferenceById(authorId))
                    .build());
            long seq = opPublisher.publish(projectId, authorId, null, "BLOCK_CREATED",
                    Map.of("blockId", block.getId()));
            return new Injected(block.getId(), seq);
        }
    }

    @TestConfiguration
    static class ProbeConfig {

        // BeanPostProcessor가 참조하므로 일반 @Bean 의존 주입보다 먼저 존재해야 한다 — static 싱글턴
        private static final QueryTap TAP = new QueryTap();

        @Bean
        QueryTap queryTap() {
            return TAP;
        }

        @Bean
        SnapshotRaceProbe snapshotRaceProbe(BlockRepository blockRepository, ProjectRepository projectRepository,
                                            UserRepository userRepository, OpPublisher opPublisher) {
            return new SnapshotRaceProbe(blockRepository, projectRepository, userRepository, opPublisher);
        }

        /** 두 리포지토리를 감싸, 감시 조회가 실행된 직후 QueryTap을 발화시킨다 */
        @Bean
        static BeanPostProcessor snapshotQueryTapProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ActivityLogRepository) && !(bean instanceof BlockRepository)) {
                        return bean;
                    }
                    ProxyFactory proxyFactory = new ProxyFactory(bean);
                    proxyFactory.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        if (TAPPED_QUERIES.contains(invocation.getMethod().getName())) {
                            TAP.fireOnce();
                        }
                        return result;
                    });
                    return proxyFactory.getProxy();
                }
            };
        }
    }
}
