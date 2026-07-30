package com.ssafy.ieumgil.domain.block.entity;

import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 일정/후보 블록 — 대시보드 보드의 최소 단위 (ERD: BLOCK).
 *
 * dayNo가 null이면 후보(POOL), 값이 있으면 해당 Day 체인에 속한다.
 * 정렬은 orderKey(fractional index) 오름차순 + 동시 삽입 대비 id tie-break.
 * 삭제는 tombstone(deletedAt) — 지연 도착한 op를 404가 아니라 410으로 구분해야 하므로 행을 남긴다.
 *
 * 체인 조회용 partial index는 JPA로 선언할 수 없다 —
 * docker/postgres/migration/002-dashboard-indexes.sql 수동 반영 필요.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "block")
public class Block extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Day 번호. null = 후보 블록(POOL) */
    private Integer dayNo;

    /** fractional index 문자열. 클라이언트가 계산해 보내고, 서버는 말단 키 부여만 한다 */
    @Column(nullable = false)
    private String orderKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BlockCategory category;

    /** 자유 텍스트 소분류 */
    @Column(length = 50)
    private String subCategory;

    @Column(nullable = false)
    private String name;

    /** 소요 시간(분, 30분 단위). 드래그 재계산의 기준이자 표시값 */
    @Builder.Default
    @Column(nullable = false)
    private Integer durationMin = 60;

    /** 일정 시작 시각. 시각 없는(느슨한) 블록은 null. 날짜가 없어 익일 도착은 미표현(v1 스코프 아웃) */
    private LocalTime startTime;

    private LocalTime endTime;

    /** true면 앵커(예약·교통) — 드래그 재계산에서 제외되고 자기 시각을 고수한다 */
    @Builder.Default
    @Column(nullable = false)
    private Boolean isTimeFixed = false;

    /** 예산(원) — 프로젝트 전체(총액) 기준. 1인당은 클라이언트가 나눠 표시만 한다 */
    @Builder.Default
    @Column(nullable = false)
    private Integer budget = 0;

    /** 세부 내용. Step 5의 detail-lock이 이 필드의 편집을 직렬화한다 */
    @Column(length = 500)
    private String detail;

    /** 위도. 장소성 카테고리(SPOT/FOOD/STAY)는 생성 시 필수 */
    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    /** 카카오 장소 ID (참조용, UNIQUE 아님 — 같은 장소를 여러 블록이 가리킬 수 있다) */
    @Column(length = 64)
    private String placeId;

    private String address;

    /** 차량 구간 표식 — ETC 카테고리 전용 */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private VehicleFlag vehicleFlag;

    /** 교통 블록 전용 메타 (mode·요금·fareConfidence 등 — ERD BLOCK 절 예시 참고) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> transportMeta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BlockSource source;

    /**
     * 필드별 서버 수신 시각(ISO-8601 문자열) — LWW 판정 기준.
     * 클라이언트 시각은 신뢰하지 않으므로 항상 서버가 기록한다. 비교는 Instant.parse로.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> fieldUpdatedAt = new HashMap<>();

    /** tombstone. null이면 살아있는 블록 */
    private LocalDateTime deletedAt;

    // ----- 연관관계 -----

    /** 프로젝트(그룹) 하드 삭제 시 함께 삭제된다(ERD: ON DELETE CASCADE) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    /**
     * 작성자. @OnDelete를 붙이지 않는다 — 탈퇴는 소프트 삭제(행 유지)라
     * 이 FK가 깨질 일이 없고, 작성자 표기는 "탈퇴한 멤버"로 계속 남아야 한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    // ----- 비즈니스 메서드 -----

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 이동(BLK-07) — 옮긴 블록 1행만 바뀐다. 다른 블록의 시각 재계산은 클라이언트 책임 */
    public void move(Integer dayNo, String orderKey) {
        this.dayNo = dayNo;
        this.orderKey = orderKey;
    }

    /**
     * LWW 갱신 대상 필드 하나를 적용한다. 값은 서비스가 타입 검증·변환을 끝낸 상태다.
     * 여기 나열된 9종이 곧 LWW 대상 필드의 전체 목록이다(dashboard-api.md).
     */
    public void applyField(String field, Object value) {
        switch (field) {
            case "name" -> this.name = (String) value;
            case "budget" -> this.budget = (Integer) value;
            case "durationMin" -> this.durationMin = (Integer) value;
            case "detail" -> this.detail = (String) value;
            case "startTime" -> this.startTime = (LocalTime) value;
            case "endTime" -> this.endTime = (LocalTime) value;
            case "isTimeFixed" -> this.isTimeFixed = (Boolean) value;
            case "vehicleFlag" -> this.vehicleFlag = (VehicleFlag) value;
            case "transportMeta" -> this.transportMeta = castTransportMeta(value);
            default -> throw new IllegalArgumentException("LWW 대상이 아닌 필드: " + field);
        }
    }

    /** 필드의 LWW 타임스탬프(서버 수신 시각)를 기록한다 */
    public void markFieldUpdated(String field, Instant receivedAt) {
        this.fieldUpdatedAt.put(field, receivedAt.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castTransportMeta(Object value) {
        return (Map<String, Object>) value;
    }
}
