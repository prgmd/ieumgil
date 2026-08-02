package com.ssafy.ieumgil.domain.chatbot.controller;

import java.util.List;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotCommandService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatbotController에 뒤늦게 붙인 {@code @GroupMember(PROJECT_ID)} 회귀 테스트.
 * {@link com.ssafy.ieumgil.domain.group.aop.GroupMemberAuthorizationTest}와 같은 패턴 —
 * 존재 확인(404) 먼저, 멤버십(403) 나중이 여기서도 지켜지는지 확인한다.
 *
 * ChatbotCommandService는 mock — 인가 계층만 검증 대상이고, 실제로 통과하면
 * GMS(Anthropic) 호출까지 이어지므로 테스트에서는 그 지점을 목으로 끊는다.
 */
@AutoConfigureMockMvc
class ChatbotAuthorizationIntegrationTest extends IntegrationTestSupport {

    private static final String MESSAGE_BODY = """
            {"message": "안녕"}
            """;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @MockitoBean
    ChatbotCommandService chatbotCommandService;

    User member;
    User outsider;
    long projectId;

    @BeforeEach
    void seed() {
        member = seedUser();
        outsider = seedUser();
        projectId = seedProject(member).getId();
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    @Test
    @DisplayName("토큰 없이 챗봇 메시지를 보내면 401")
    void withoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/chatbot/messages", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(MESSAGE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비멤버가 남의 프로젝트 챗봇을 두들기면 403(GROUP403)")
    void outsiderReturns403() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/chatbot/messages", projectId)
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content(MESSAGE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP403"));
    }

    @Test
    @DisplayName("존재하지 않는 프로젝트는 비멤버에게도 404 — 403보다 404가 먼저다")
    void unknownProjectReturns404EvenForOutsider() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/chatbot/messages", 9_999_999L)
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content(MESSAGE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT404"));
    }

    @Test
    @DisplayName("멤버는 정상적으로 챗봇을 쓸 수 있다 — 인가가 과하게 막지 않는지")
    void memberCanSendMessage() throws Exception {
        when(chatbotCommandService.sendMessage(eq(projectId), eq(member.getId()), any()))
                .thenReturn(new ChatbotResDTO.MessageResult("안녕하세요", List.of()));

        mockMvc.perform(post("/api/projects/{projectId}/chatbot/messages", projectId)
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content(MESSAGE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reply").value("안녕하세요"));
    }
}
