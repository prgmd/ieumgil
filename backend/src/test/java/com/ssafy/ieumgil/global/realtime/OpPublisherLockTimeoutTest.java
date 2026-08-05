package com.ssafy.ieumgil.global.realtime;

import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.apiPayload.code.GeneralErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * op 락 획득 상한(tryLock) 안전망 — 락 순서 역전 데드락이 "전체 멈춤 → 재기동"이 아니라
 * "늦게 온 요청 하나의 503"으로 끝나는지 검증한다.
 *
 * 실제 데드락(자바 락 vs DB 행 락 교차)은 커밋 시점 flush 타이밍에 의존해 재현이 취약하므로,
 * 여기서는 그 대리 시나리오를 쓴다: 트랜잭션 하나가 publish 후 커밋을 미뤄 락을 상한보다
 * 오래 쥐고 있을 때(데드락에서 락이 안 풀리는 상황과 동형), 뒤에 온 publish가
 * ① 무한 대기하지 않고 ② OP_LOCK_TIMEOUT으로 실패하며 ③ 앞 트랜잭션이 끝나면
 * 락이 정상 반환되는지를 본다. ③이 없으면 안전망이 새 장애(락 유실)를 만든 것이다.
 *
 * 상한을 300ms로 줄여 돌린다(기본 5초면 테스트가 그만큼 잠든다). 별도 프로퍼티라
 * 컨텍스트가 새로 뜨지만 컨테이너는 static 공유라 추가 비용은 컨텍스트 기동뿐이다.
 */
@TestPropertySource(properties = "realtime.op-lock-timeout-ms=300")
class OpPublisherLockTimeoutTest extends IntegrationTestSupport {

    @Autowired
    OpPublisher opPublisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("락을 상한보다 오래 쥔 트랜잭션이 있으면 뒤 publish는 멈추지 않고 OP_LOCK_TIMEOUT으로 실패한다")
    void publishFailsFastInsteadOfHangingWhenLockIsHeld() throws Exception {
        User user = seedUser();
        Project project = seedProject(user);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);   // 홀더가 락을 잡았다
        CountDownLatch release = new CountDownLatch(1);    // 홀더에게 커밋을 허락한다
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            // 홀더: publish로 락을 잡은 뒤 커밋을 미룬다 — 락은 afterCompletion까지 안 풀린다
            Future<Long> holder = pool.submit(() -> tx.execute(status -> {
                long seq = opPublisher.publish(project.getId(), user.getId(), null,
                        "BLOCK_CREATED", Map.of("blockId", 1L));
                lockHeld.countDown();
                try {
                    // 뒤 요청의 상한(300ms)보다 확실히 길게 보유한다. 테스트 실패 시에도
                    // 최대 10초 뒤 풀리도록 await 상한을 둔다 — 테스트가 교착을 만들면 본말전도다
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return seq;
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).as("홀더가 락을 잡았다").isTrue();

            // 뒤 요청: 무한 대기가 아니라 상한(300ms) 안팎에서 OP_LOCK_TIMEOUT으로 끊겨야 한다
            long begin = System.nanoTime();
            assertThatThrownBy(() -> opPublisher.publish(project.getId(), user.getId(), null,
                    "BLOCK_MOVED", Map.of("blockId", 1L)))
                    .isInstanceOfSatisfying(CustomException.class, e ->
                            assertThat(e.getCode()).isEqualTo(GeneralErrorCode.OP_LOCK_TIMEOUT));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertThat(elapsedMs).as("상한 근처에서 포기했다 (무한 대기 아님)").isLessThan(5_000);

            // 홀더가 커밋하면 락이 반환되고, 그 뒤의 publish는 정상 성공해야 한다(락 유실 없음)
            release.countDown();
            long holderSeq = holder.get(10, TimeUnit.SECONDS);

            long nextSeq = opPublisher.publish(project.getId(), user.getId(), null,
                    "BLOCK_DELETED", Map.of("blockId", 1L));
            assertThat(nextSeq).isGreaterThan(holderSeq);
        } finally {
            release.countDown();   // 실패 경로에서도 홀더를 반드시 풀어준다
            pool.shutdownNow();
        }
    }
}
