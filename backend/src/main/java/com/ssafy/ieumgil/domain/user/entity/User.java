package com.ssafy.ieumgil.domain.user.entity;

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

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "users")
public class User extends BaseTimeEntity {

    private static final String WITHDRAWN_NICKNAME = "탈퇴한 멤버";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 카카오 고유 ID. 탈퇴 시 null로 지워 원본 소셜 ID를 파기한다.
     * PostgreSQL은 UNIQUE 컬럼에 NULL을 여러 개 허용하므로 탈퇴자가 늘어도 충돌하지 않는다.
     */
    @Column(unique = true)
    private Long kakaoId;

    @Column(nullable = false, length = 30)
    private String nickname;

    /** 카카오 CDN URL이 길 수 있어 512로 잡는다(ERD 기준) */
    @Column(length = 512)
    private String profileImageUrl;

    /** 탈퇴 시각. null이면 활성 회원 */
    private LocalDateTime deletedAt;

    public void updateProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    /**
     * 회원 탈퇴(AUTH-05) — "재가입 = 신규 계정" 정책.
     * 행은 유지해 블록 작성자(author_id) 참조를 보존하고, 개인정보만 지운다.
     * 같은 카카오 계정으로 재가입하면 kakaoId가 null이라 조회되지 않아 새 행이 생성된다.
     */
    public void withdraw() {
        this.kakaoId = null;
        this.nickname = WITHDRAWN_NICKNAME;
        this.profileImageUrl = null;
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isWithdrawn() {
        return deletedAt != null;
    }
}
