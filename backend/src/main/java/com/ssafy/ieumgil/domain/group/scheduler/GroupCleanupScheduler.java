package com.ssafy.ieumgil.domain.group.scheduler;

import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 소프트 삭제된 그룹의 하드 삭제 담당 (MY-04: 소프트 삭제 30일 후 완전 삭제).
 *
 * 삭제 API(DELETE /groups/{groupId})는 deletedAt만 남기고, 실제 행 제거는 여기서 일괄 처리한다.
 * 그룹 행이 지워지면 group_member·project는 FK의 ON DELETE CASCADE로 함께 삭제되므로
 * 소프트 삭제 상태에서 나가지 못해 남아 있던 멤버십 행도 이때 함께 정리된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupCleanupScheduler {

    /** 소프트 삭제 후 보존 기간 (MY-04: 30일) */
    private static final int RETENTION_DAYS = 30;

    private final TravelGroupRepository travelGroupRepository;

    /** 매일 04:00(서버 시각) 실행. 하루 안에 만료분을 지우면 되는 작업이라 새벽 1회면 충분하다 */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void hardDeleteExpiredGroups() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);

        int deletedCount = travelGroupRepository.deleteAllSoftDeletedBefore(threshold);

        if (deletedCount > 0) {
            log.info("소프트 삭제 {}일 경과 그룹 {}건 하드 삭제 완료", RETENTION_DAYS, deletedCount);
        }
    }
}
