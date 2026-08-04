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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 그룹 내 여행 프로젝트 (대시보드 보드 단위).
 * 예산은 프로젝트 전체(총액) 기준이며, budgetHeadcount는 "1인당 = 총액 ÷ 인원" 표시용이다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
// 카드 목록·그룹별 개수 집계가 전부 (group_id, deleted_at) 조건이다.
// PostgreSQL은 FK에 인덱스를 자동 생성하지 않으므로 직접 선언한다.
// (배포 DB는 ddl-auto=none — docker/postgres/migration/001-indexes.sql 수동 반영 필요)
@Table(name = "project",
        indexes = @Index(name = "ix_project_group", columnList = "group_id, deleted_at"))
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transport_prefs", columnDefinition = "jsonb")
    private List<TransportPref> transportPrefs;

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

    /**
     * 챗봇이 저장하는 여행 키워드. 대시보드 스냅샷 응답에 그대로 실린다.
     *
     * 쓰는 쪽은 챗봇(POST /projects/{id}/keywords, dashboard-ai 담당)이고
     * 읽는 쪽은 스냅샷 API다. 두 파트가 공유하는 컬럼이라 어느 한쪽 구현을 기다리지 않도록
     * 컬럼만 먼저 잡아둔다. 갱신 메서드는 쓰는 쪽에서 의미(교체/누적)를 정한 뒤 추가한다.
     *
     * JSONB 매핑은 Hibernate 6 기본 기능이라 별도 라이브러리가 필요 없다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> keywords;

    /** 소프트 삭제 시각. null이면 살아있는 프로젝트 */
    private LocalDateTime deletedAt;

    // ----- 연관관계 -----

    /** 그룹이 하드 삭제되면 프로젝트도 함께 삭제된다(ERD: ON DELETE CASCADE) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TravelGroup travelGroup;

    // ----- 비즈니스 메서드 -----

    /**
     * 상태 전환 (GRP-10). PLANNING↔DONE 양방향이며 DONE이어도 쓰기를 막지 않는다(NFR-05).
     * doneAt은 상태의 파생값이라 여기서 함께 관리한다 — 따로 두면 반드시 어긋난다.
     */
    public void changeStatus(ProjectStatus status) {
        this.status = status;
        this.doneAt = (status == ProjectStatus.DONE) ? LocalDateTime.now() : null;
    }

    /** 정산 인원 지정 (BGT-03). null = "그룹 멤버 수 연동으로 복귀"라는 명시적 의도 */
    public void changeBudgetHeadcount(Integer headcount) {
        this.budgetHeadcount = headcount;
    }

    /** 총 예산 변경. null = "예산 미설정으로 초기화"라는 명시적 의도 */
    public void updateBudget(Integer budget) {
        this.targetBudget = budget;
    }

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

    /** 이동수단 선호 부분 수정. 빈/null이면 무시(불변) — 다음 후보 계산부터 적용, 기존 블록은 재계산하지 않는다 */
    public void changeTransportPref(List<TransportPref> transportPrefs) {
        if (transportPrefs == null || transportPrefs.isEmpty()) {
            return;
        }
        this.transportPrefs = transportPrefs.stream().distinct().toList();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
