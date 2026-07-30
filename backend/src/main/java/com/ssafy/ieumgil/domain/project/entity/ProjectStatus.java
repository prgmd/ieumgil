package com.ssafy.ieumgil.domain.project.entity;

/**
 * 프로젝트 진행 상태. PLANNING ↔ DONE 양방향 전환 가능하며,
 * DONE이어도 쓰기를 막지 않는다(NFR-05).
 */
public enum ProjectStatus {
    PLANNING,
    DONE
}
