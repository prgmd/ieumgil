package com.ssafy.ieumgil.domain.activitylog.entity;

import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 실시간 동기화 op 저널 (ERD: ACTIVITY_LOG).
 *
 * 모든 변경 API가 브로드캐스트할 op 전문을 여기 그대로 저장한다 —
 * 재연결 시 재전송(GET /projects/{id}/ops?afterSeq=)은 이 저장분을 가공 없이 돌려주므로
 * "재전송 = 브로드캐스트했던 것과 동일"이 구조적으로 보장된다(NFR-01).
 *
 * 쓰기 전용 append 테이블이다. UPDATE/DELETE 하지 않는다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "activity_log",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_activity_log_project_seq", columnNames = {"project_id", "seq"}))
public class ActivityLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** op 종류 (예: BLOCK_CREATED, PROJECT_STATUS_CHANGED) */
    @Column(nullable = false, length = 40)
    private String opType;

    /** 브로드캐스트 op 전문 그대로 (seq·type·actorId·clientId·payload 포함) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /** 프로젝트 내 단조 증가 시퀀스 — 유실 감지와 재전송의 기준 */
    @Column(nullable = false)
    private long seq;

    // ----- 연관관계 -----

    /** 프로젝트 하드 삭제 시 저널도 함께 삭제된다(ERD: ON DELETE CASCADE) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    /** op 발생 주체. 탈퇴는 소프트 삭제(행 유지)라 FK가 깨지지 않는다 — @OnDelete 없음 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private User user;
}
