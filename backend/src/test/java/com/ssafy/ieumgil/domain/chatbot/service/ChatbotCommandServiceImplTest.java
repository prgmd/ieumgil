package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatbotCommandServiceImplTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatHistoryStore chatHistoryStore;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private FestivalQueryService festivalQueryService;

    @Mock
    private PlaceQueryService placeQueryService;

    private ChatbotCommandServiceImpl chatbotCommandService;

    @BeforeEach
    void setUp() {
        chatbotCommandService = new ChatbotCommandServiceImpl(
                chatModel, chatHistoryStore, projectRepository, festivalQueryService, placeQueryService
        );
    }

    @Test
    void firstMessageWithNoHistoryReturnsGmsReply() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("안녕하세요! 여행 계획 도와드릴게요."));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("안녕")
        );

        assertThat(result.reply()).isEqualTo("안녕하세요! 여행 계획 도와드릴게요.");
    }

    @Test
    void existingHistoryIsAppendedAfterSuccess() {
        List<ChatTurn> existing = List.of(new ChatTurn(ChatTurn.ROLE_USER, "이전 질문"));
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(existing);
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("이어지는 답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("다음 질문"));

        verify(chatHistoryStore).appendExchange(
                eq(1L), eq(1L),
                eq(new ChatTurn(ChatTurn.ROLE_USER, "다음 질문")),
                eq(new ChatTurn(ChatTurn.ROLE_ASSISTANT, "이어지는 답변"))
        );
    }

    @Test
    void gmsFailureThrowsChatbotException() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕")))
                .isInstanceOf(ChatbotException.class);
    }

    @Test
    void resolvesFestivalToolWhenProjectHasDestinationAndDates() {
        Project project = Project.builder()
                .destination("제주도")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        Optional<FestivalRecommendationTool> tool = chatbotCommandService.resolveFestivalTool(1L);

        assertThat(tool).isPresent();
    }

    @Test
    void skipsFestivalToolWhenDestinationBlank() {
        Project project = Project.builder()
                .destination(null)
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        assertThat(chatbotCommandService.resolveFestivalTool(1L)).isEmpty();
    }

    @Test
    void skipsFestivalToolWhenDatesMissing() {
        Project project = Project.builder()
                .destination("제주도")
                .startDate(null)
                .endDate(null)
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        assertThat(chatbotCommandService.resolveFestivalTool(1L)).isEmpty();
    }

    @Test
    void skipsFestivalToolWhenDestinationUnmatched() {
        Project project = Project.builder()
                .destination("도쿄")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        assertThat(chatbotCommandService.resolveFestivalTool(1L)).isEmpty();
    }

    @Test
    void skipsFestivalToolWhenProjectNotFound() {
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThat(chatbotCommandService.resolveFestivalTool(1L)).isEmpty();
    }

    @Test
    void sendMessageRegistersFestivalToolWhenProjectResolvable() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder()
                .destination("제주도")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("여기서 뭐 할만한거 있어?"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("여기서 뭐 할만한거 있어?"));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder("searchPlaces", "getWalkingRoute", "getTaxiRoute", "findFestivalsForCurrentTrip");
    }

    @Test
    void resolvesKakaoToolsWhenDestinationPresent() {
        Project project = Project.builder().destination("제주도").build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        List<Object> tools = chatbotCommandService.resolveKakaoTools(1L);

        assertThat(tools).hasSize(3);
        assertThat(tools.get(0)).isInstanceOf(KakaoPlaceSearchTool.class);
        assertThat(tools.get(1)).isInstanceOf(WalkingRouteTool.class);
        assertThat(tools.get(2)).isInstanceOf(TaxiRouteTool.class);
    }

    @Test
    void skipsKakaoToolsWhenDestinationBlank() {
        Project project = Project.builder().destination(null).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        assertThat(chatbotCommandService.resolveKakaoTools(1L)).isEmpty();
    }

    @Test
    void skipsKakaoToolsWhenProjectNotFound() {
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThat(chatbotCommandService.resolveKakaoTools(1L)).isEmpty();
    }

    @Test
    void sendMessageRegistersKakaoToolsWhenDestinationPresent() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("근처 카페 추천해줘"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("근처 카페 추천해줘"));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .contains("searchPlaces", "getWalkingRoute", "getTaxiRoute");
    }

    private ChatResponse canned(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
