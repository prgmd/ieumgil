package com.ssafy.ieumgil.domain.festival.entity;

import com.ssafy.ieumgil.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "festival")
public class Festival extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String contentId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String category;

    private String lDongRegnCd;

    private String lDongSignguCd;

    private String addr;

    private Double lat;

    private Double lng;

    @Column(nullable = false)
    private LocalDate eventStartDate;

    @Column(nullable = false)
    private LocalDate eventEndDate;

    private String firstImage;

    @Column(length = 500)
    private String homepage;

    public void update(String title, String category, String lDongRegnCd, String lDongSignguCd,
                        String addr, Double lat, Double lng,
                        LocalDate eventStartDate, LocalDate eventEndDate, String firstImage,
                        String homepage) {
        this.title = title;
        this.category = category;
        this.lDongRegnCd = lDongRegnCd;
        this.lDongSignguCd = lDongSignguCd;
        this.addr = addr;
        this.lat = lat;
        this.lng = lng;
        this.eventStartDate = eventStartDate;
        this.eventEndDate = eventEndDate;
        this.firstImage = firstImage;
        if (homepage != null) {
            this.homepage = homepage;
        }
    }
}
