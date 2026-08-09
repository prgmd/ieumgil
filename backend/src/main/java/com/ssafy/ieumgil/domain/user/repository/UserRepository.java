package com.ssafy.ieumgil.domain.user.repository;

import com.ssafy.ieumgil.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByKakaoId(Long kakaoId);

    /**
     * 활성 회원(탈퇴하지 않은) 존재 확인 — 인증된 요청마다 호출된다.
     *
     * <p>exists + PK 조건이라 엔티티 로딩 없이 인덱스 조회 1회로 끝난다.
     * 탈퇴 회원과 없는 회원을 구분하지 않는다 — 인증 관점에서는 둘 다 401이다.
     */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
