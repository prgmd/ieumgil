package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.FlightScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.TrainScheduleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private FestivalQueryService festivalQueryService;

    @Mock
    private PlaceQueryService placeQueryService;

    @Mock
    private TrainScheduleProvider trainScheduleProvider;

    @Mock
    private BusScheduleProvider busScheduleProvider;

    @Mock
    private FlightScheduleProvider flightScheduleProvider;

    private ChatbotCommandServiceImpl chatbotCommandService;

    @BeforeEach
    void setUp() {
        chatbotCommandService = new ChatbotCommandServiceImpl(
                chatModel, chatHistoryStore, projectRepository, groupMemberRepository,
                festivalQueryService, placeQueryService,
                new TrainScheduleTool(trainScheduleProvider),
                new BusScheduleTool(busScheduleProvider),
                new FlightScheduleTool(flightScheduleProvider)
        );
    }

    @Test
    void firstMessageWithNoHistoryReturnsGmsReply() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("안녕하세요! 여행 계획 도와드릴게요."));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null)
        );

        assertThat(result.reply()).isEqualTo("안녕하세요! 여행 계획 도와드릴게요.");
    }

    @Test
    void existingHistoryIsAppendedAfterSuccess() {
        List<ChatTurn> existing = List.of(new ChatTurn(ChatTurn.ROLE_USER, "이전 질문"));
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(existing);
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("이어지는 답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("다음 질문", null));

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

        assertThatThrownBy(() -> chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null)))
                .isInstanceOf(ChatbotException.class);
    }

    @Test
    void resolvesFestivalToolWhenProjectHasDestinationAndDates() {
        Project project = Project.builder()
                .destination("제주도")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        Optional<FestivalRecommendationTool> tool = chatbotCommandService.resolveFestivalTool(Optional.of(project), new CandidateCollector());

        assertThat(tool).isPresent();
    }

    @Test
    void skipsFestivalToolWhenDestinationBlank() {
        Project project = Project.builder()
                .destination(null)
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        assertThat(chatbotCommandService.resolveFestivalTool(Optional.of(project), new CandidateCollector())).isEmpty();
    }

    @Test
    void skipsFestivalToolWhenDatesMissing() {
        Project project = Project.builder()
                .destination("제주도")
                .startDate(null)
                .endDate(null)
                .build();
        assertThat(chatbotCommandService.resolveFestivalTool(Optional.of(project), new CandidateCollector())).isEmpty();
    }

    @Test
    void skipsFestivalToolWhenDestinationUnmatched() {
        Project project = Project.builder()
                .destination("도쿄")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        assertThat(chatbotCommandService.resolveFestivalTool(Optional.of(project), new CandidateCollector())).isEmpty();
    }

    @Test
    void skipsFestivalToolWhenProjectNotFound() {
        assertThat(chatbotCommandService.resolveFestivalTool(Optional.empty(), new CandidateCollector())).isEmpty();
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

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("여기서 뭐 할만한거 있어?", null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "searchPlaces", "getWalkingRoute", "getTaxiRoute",
                        "getTrainSchedule", "getBusSchedule", "getFlightSchedule",
                        "findFestivalsForCurrentTrip"
                );
    }

    @Test
    @DisplayName("시스템 프롬프트에 여행 메타데이터가 주입된다 — 모든 대화에 항상 실린다")
    void sendMessageInjectsTripMetadataIntoSystemPrompt() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder()
                .destination("제주도")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .budgetHeadcount(4)
                .transportPref(TransportPref.CAR)
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("뭐 먹을까?", null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String systemText = promptCaptor.getValue().getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
        assertThat(systemText).contains("destination: 제주도");
        assertThat(systemText).contains("dates: 2026-08-01 ~ 2026-08-03 (3 days)");
        assertThat(systemText).contains("headcount: 4");
        assertThat(systemText).contains("transport: 자가용");
    }

    @Test
    @DisplayName("정산 인원이 지정돼 있으면 그룹 인원을 세지 않는다 — 쿼리 절약이 설계 의도다")
    void doesNotCountGroupMembersWhenHeadcountIsSet() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").budgetHeadcount(4).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null));

        verify(groupMemberRepository, never()).countMembers(any());
    }

    @Test
    @DisplayName("정산 인원이 없으면 그룹 멤버 수로 폴백한다 (BGT-03)")
    void fallsBackToGroupMemberCountWhenHeadcountMissing() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder()
                .destination("제주도")
                .travelGroup(TravelGroup.builder().id(9L).build())
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(groupMemberRepository.countMembers(9L)).thenReturn(3L);
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String systemText = promptCaptor.getValue().getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
        assertThat(systemText).contains("headcount: 3");
    }

    @Test
    @DisplayName("프로젝트는 메시지당 한 번만 조회한다 — 메타데이터와 tool 구성이 같은 로드를 공유한다")
    void loadsProjectOncePerMessage() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder()
                .destination("제주도")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .budgetHeadcount(4)
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null));

        verify(projectRepository, times(1)).findByIdAndDeletedAtIsNull(1L);
    }

    @Test
    void resolvesKakaoToolsWhenDestinationPresent() {
        Project project = Project.builder().destination("제주도").build();
        List<Object> tools = chatbotCommandService.resolveKakaoTools(Optional.of(project), new CandidateCollector());

        assertThat(tools).hasSize(3);
        assertThat(tools.get(0)).isInstanceOf(KakaoPlaceSearchTool.class);
        assertThat(tools.get(1)).isInstanceOf(WalkingRouteTool.class);
        assertThat(tools.get(2)).isInstanceOf(TaxiRouteTool.class);
    }

    @Test
    void skipsKakaoToolsWhenDestinationBlank() {
        Project project = Project.builder().destination(null).build();
        assertThat(chatbotCommandService.resolveKakaoTools(Optional.of(project), new CandidateCollector())).isEmpty();
    }

    @Test
    void skipsKakaoToolsWhenProjectNotFound() {
        assertThat(chatbotCommandService.resolveKakaoTools(Optional.empty(), new CandidateCollector())).isEmpty();
    }

    @Test
    void sendMessageRegistersKakaoToolsWhenDestinationPresent() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("근처 카페 추천해줘"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("근처 카페 추천해줘", null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder("searchPlaces", "getWalkingRoute", "getTaxiRoute",
                        "getTrainSchedule", "getBusSchedule", "getFlightSchedule");
    }

    @Test
    void sendMessageAlwaysRegistersScheduleTools() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("서울에서 부산 기차 몇시에 있어?"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("서울에서 부산 기차 몇시에 있어?", null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder("getTrainSchedule", "getBusSchedule", "getFlightSchedule");
    }

    private ChatResponse canned(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("추천이 없으면 candidates는 빈 배열이다 — null이면 프론트가 방어 코드를 써야 한다")
    void candidatesIsEmptyArrayWhenNoToolRecommended() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("안녕하세요"));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null));

        assertThat(result.candidates()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("tool이 모은 후보가 응답 candidates까지 흐른다")
    void toolCollectedCandidatesReachTheResponse() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").budgetHeadcount(4).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        PlaceResDTO.Place place = PlaceResDTO.Place.builder()
                .placeId("77").name("스타벅스").address("제주 서귀포시")
                .lat(33.45).lng(126.93).category("카페")
                .build();
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenReturn(List.of(place));
        // 실제 tool-calling 루프 대신, 모델이 tool을 부른 상황을 tool 직접 호출로 재현한다
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            options.getToolCallbacks().stream()
                    .filter(tc -> tc.getToolDefinition().name().equals("searchPlaces"))
                    .findFirst()
                    .orElseThrow()
                    .call("{\"keyword\":\"카페\"}");
            return canned("카페 추천드려요");
        });

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천해줘", null));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).placeId()).isEqualTo("77");
        assertThat(result.candidates().get(0).category()).isEqualTo(BlockCategory.FOOD);
    }
}
