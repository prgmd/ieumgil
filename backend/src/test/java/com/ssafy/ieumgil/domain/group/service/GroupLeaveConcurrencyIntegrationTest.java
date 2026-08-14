package com.ssafy.ieumgil.domain.group.service;

import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2인 그룹에서 두 멤버가 동시에 탈퇴해도 그룹이 반드시 삭제되는지 — 그룹 행 잠금 회귀 테스트.
 *
 * 배경: "마지막 1인이면 하드 삭제" 판정이 count 조회 → 삭제의 check-then-act라, 잠금 없이
 * 동시 탈퇴하면 둘 다 count=2를 읽어 아무도 그룹을 지우지 않는다 — 멤버 0명인 좀비 그룹이
 * 조회도 삭제도 불가능한 채 영구 잔존한다. TravelGroupRepository.findByIdForUpdate의
 * @Lock을 제거하면 이 테스트가 (간헐적으로) 깨진다.
 */
class GroupLeaveConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    GroupCommandService groupCommandService;

    @Test
    @DisplayName("2인 그룹에서 동시에 탈퇴하면 좀비 그룹 없이 그룹이 삭제된다")
    void concurrentLeaveDeletesGroup() throws InterruptedException {
        User userA = seedUser();
        User userB = seedUser();
        TravelGroup group = travelGroupRepository.save(TravelGroup.builder()
                .name("동시탈퇴그룹")
                .inviteCode(UUID.randomUUID().toString().substring(0, 8))
                .inviteExpiresAt(LocalDateTime.now().plusDays(7))
                .build());
        groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(userA).build());
        groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(userB).build());
        Long groupId = group.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();

        for (Long leaverId : List.of(userA.getId(), userB.getId())) {
            pool.submit(() -> {
                try {
                    start.await();
                    groupCommandService.leaveGroup(leaverId, groupId);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures.get()).isZero();

        // 핵심 검증 — 그룹이 남아 있으면 아무도 접근할 수 없는 0명 좀비 그룹이다
        assertThat(travelGroupRepository.findById(groupId)).isEmpty();
        assertThat(groupMemberRepository.countMembers(groupId)).isZero();
    }
}
