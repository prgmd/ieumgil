package com.ssafy.ieumgil.domain.project.entity;

import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 그룹 내 여행 프로젝트 (대시보드 보드 단위).
 * 예산은 프로젝트 전체(총액) 기준이며, budgetHeadcount는 "1인당 = 총액 ÷ 인원" 표시용이다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "project")
public class Project extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String destination;

    private LocalDate startDate;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TransportPref transportPref;

    /** 정산 인원(1인당 표시용). null이면 조회 시점 그룹 멤버 수를 따른다(BGT-03) */
    private Integer budgetHeadcount;

    /** 프로젝트 전체 목표 예산 */
    private Integer targetBudget;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProjectStatus status = ProjectStatus.PLANNING;

    /** 완료 시각. PLANNING으로 되돌리면 null */
    private LocalDateTime doneAt;

    /** 카드 썸네일 그라데이션 키 */
    @Column(length = 30)
    private String themeColor;

    /** 소프트 삭제 시각. null이면 살아있는 프로젝트 */
    private LocalDateTime deletedAt;

    // ----- 연관관계 -----

    /** 그룹이 하드 삭제되면 프로젝트도 함께 삭제된다(ERD: ON DELETE CASCADE) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TravelGroup travelGroup;

    // ----- 비즈니스 메서드 -----

    /** 이름·기간 부분 수정. null인 인자는 건드리지 않는다(PATCH 시맨틱) */
    public void updateInfo(String name, LocalDate startDate, LocalDate endDate) {
        if (name != null) {
            this.name = name;
        }
        if (startDate != null) {
            this.startDate = startDate;
        }
        if (endDate != null) {
            this.endDate = endDate;
        }
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
