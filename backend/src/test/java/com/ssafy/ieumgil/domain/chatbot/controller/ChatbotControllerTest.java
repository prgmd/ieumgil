package com.ssafy.ieumgil.domain.chatbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotCommandService;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// JwtAuthenticationFilter가 이 슬라이스 컨텍스트에 없는 JwtProvider를 요구해 필터 체인 자체를 끈다.
// 대신 principal에 Long memberId를 직접 심어(JwtAuthenticationFilter와 동일한 형태) @AuthenticationPrincipal 바인딩만 검증한다.
@WebMvcTest(ChatbotController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatbotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatbotCommandService chatbotCommandService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    void sendMessageReturnsOkWithReply() throws Exception {
        when(chatbotCommandService.sendMessage(eq(1L), any(), any())).thenReturn(new ChatbotResDTO.MessageResult("안녕하세요", List.of()));

        mockMvc.perform(post("/api/projects/1/chatbot/messages")
                        .with(authentication(memberAuthentication(1L)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new ChatbotReqDTO.SendMessage("안녕", null)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.reply").value("안녕하세요"));
    }

    @Test
    void blankMessageReturns400() throws Exception {
        mockMvc.perform(post("/api/projects/1/chatbot/messages")
                        .with(authentication(memberAuthentication(1L)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new ChatbotReqDTO.SendMessage("", null)
                        )))
                .andExpect(status().isBadRequest());
    }

    private UsernamePasswordAuthenticationToken memberAuthentication(Long memberId) {
        return new UsernamePasswordAuthenticationToken(
                memberId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
