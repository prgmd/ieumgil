package com.ssafy.ieumgil.domain.chatbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotCommandService;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
                                new ChatbotReqDTO.SendMessage("안녕", null, null)
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
                                new ChatbotReqDTO.SendMessage("", null, null)
                        )))
                .andExpect(status().isBadRequest());
    }

    private UsernamePasswordAuthenticationToken memberAuthentication(Long memberId) {
        return new UsernamePasswordAuthenticationToken(
                memberId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    @DisplayName("MAP 모드 요청의 mode·mapContext가 JSON에서 그대로 역직렬화된다")
    void mapModeRequestDeserializesModeAndViewport() throws Exception {
        when(chatbotCommandService.sendMessage(eq(1L), any(), any()))
                .thenReturn(new ChatbotResDTO.MessageResult("이 근처 카페예요", List.of()));

        mockMvc.perform(post("/api/projects/1/chatbot/messages")
                        .with(authentication(memberAuthentication(1L)))
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "카페 추천해줘",
                                  "mode": "MAP",
                                  "mapContext": {
                                    "swLat": 33.44, "swLng": 126.93,
                                    "neLat": 33.47, "neLng": 126.95
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatbotReqDTO.SendMessage> captor =
                ArgumentCaptor.forClass(ChatbotReqDTO.SendMessage.class);
        verify(chatbotCommandService).sendMessage(eq(1L), any(), captor.capture());
        ChatbotReqDTO.SendMessage sent = captor.getValue();
        assertThat(sent.modeOrDefault()).isEqualTo(ChatbotMode.MAP);
        assertThat(sent.mapContext().swLat()).isEqualTo(33.44);
        assertThat(sent.mapContext().neLng()).isEqualTo(126.95);
    }

    @Test
    @DisplayName("뷰포트 좌표가 위경도 범위를 벗어나면 400 — 애노테이션 검증이 실제로 걸린다")
    void outOfRangeViewportReturns400() throws Exception {
        mockMvc.perform(post("/api/projects/1/chatbot/messages")
                        .with(authentication(memberAuthentication(1L)))
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "카페 추천해줘",
                                  "mode": "MAP",
                                  "mapContext": {
                                    "swLat": 991.0, "swLng": 126.93,
                                    "neLat": 33.47, "neLng": 126.95
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
