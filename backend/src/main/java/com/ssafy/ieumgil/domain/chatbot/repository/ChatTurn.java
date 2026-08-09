package com.ssafy.ieumgil.domain.chatbot.repository;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;

import java.util.List;

/**
 * 프로젝트+멤버 이력의 한 턴.
 *
 * <p>{@code role}/{@code content}는 LLM 프롬프트로 들어가고, {@code candidates}는 화면 복원 전용이다
 * — 프롬프트 조립(toMessage)은 candidates를 읽지 않아 LLM 컨텍스트를 오염시키지 않는다.
 */
public record ChatTurn(String role, String content, List<ChatbotResDTO.Candidate> candidates) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /** candidates가 null(옛 JSON·유저 턴)이면 빈 배열로 정규화한다 — 이후 null 체크를 없앤다. */
    public ChatTurn {
        candidates = candidates == null ? List.of() : candidates;
    }

    /** 후보가 없는 턴(유저 발화 등). */
    public ChatTurn(String role, String content) {
        this(role, content, List.of());
    }
}
