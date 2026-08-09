package com.ssafy.ieumgil.domain.chatbot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.RequestScopedBoard;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.PlaceRanker;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
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
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
    private com.ssafy.ieumgil.domain.block.repository.BlockRepository blockRepository;

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
                chatModel, chatHistoryStore, projectRepository, groupMemberRepository, blockRepository,
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
                1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null)
        );

        assertThat(result.reply()).isEqualTo("안녕하세요! 여행 계획 도와드릴게요.");
    }

    @Test
    void existingHistoryIsAppendedAfterSuccess() {
        List<ChatTurn> existing = List.of(new ChatTurn(ChatTurn.ROLE_USER, "이전 질문"));
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(existing);
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("이어지는 답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("다음 질문", null, null, null));

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

        assertThatThrownBy(() -> chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null)))
                .isInstanceOf(ChatbotException.class);
    }

    @Test
    @DisplayName("GMS 실패 원인을 로그에 남긴다 — 401·429·파싱 오류·tool 예외가 전부 같은 한 줄이 되면 운영에서 추적이 안 된다")
    void gmsFailureLogsTheCause() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("401 Unauthorized"));

        Logger logger = (Logger) LoggerFactory.getLogger(ChatbotCommandServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> chatbotCommandService.sendMessage(
                    1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null)))
                    .isInstanceOf(ChatbotException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            // 스택까지 남아야 진짜 원인을 볼 수 있다
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).contains("401");
        });
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
    void registersFestivalToolEvenWhenDestinationNotAProvince() {
        // 시/도로 매칭 안 되는(해외) 목적지여도 목적지·기간만 있으면 툴은 등록된다.
        // 어느 시/도인지의 판정은 호출 시점에 모델이 준 region 인자로 하며, 여기서 게이팅하지 않는다.
        Project project = Project.builder()
                .destination("도쿄")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .build();
        assertThat(chatbotCommandService.resolveFestivalTool(Optional.of(project), new CandidateCollector())).isPresent();
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

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("여기서 뭐 할만한거 있어?", null, null, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "searchPlaces", "getWalkingRoute", "getTaxiRoute",
                        "getTrainSchedule", "getBusSchedule", "getFlightSchedule",
                        "findFestivalsForCurrentTrip", "getCurrentPlan"
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
                .transportPrefs(List.of(TransportPref.CAR))
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("뭐 먹을까?", null, null, null));

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

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null));

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

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null));

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

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null));

        verify(projectRepository, times(1)).findByIdAndDeletedAtIsNull(1L);
    }

    @Test
    void resolvesKakaoToolsWhenDestinationPresent() {
        Project project = Project.builder().destination("제주도").build();
        List<Object> tools = chatbotCommandService.resolveKakaoTools(new RequestScopedBoard(() -> java.util.List.of()), Optional.of(project), new CandidateCollector());

        assertThat(tools).hasSize(3);
        assertThat(tools.get(0)).isInstanceOf(KakaoPlaceSearchTool.class);
        assertThat(tools.get(1)).isInstanceOf(WalkingRouteTool.class);
        assertThat(tools.get(2)).isInstanceOf(TaxiRouteTool.class);
    }

    @Test
    void skipsKakaoToolsWhenDestinationBlank() {
        Project project = Project.builder().destination(null).build();
        assertThat(chatbotCommandService.resolveKakaoTools(new RequestScopedBoard(() -> java.util.List.of()), Optional.of(project), new CandidateCollector())).isEmpty();
    }

    @Test
    void skipsKakaoToolsWhenProjectNotFound() {
        assertThat(chatbotCommandService.resolveKakaoTools(new RequestScopedBoard(() -> java.util.List.of()), Optional.empty(), new CandidateCollector())).isEmpty();
    }

    @Test
    void sendMessageRegistersKakaoToolsWhenDestinationPresent() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("근처 카페 추천해줘"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("근처 카페 추천해줘", null, null, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder("searchPlaces", "getWalkingRoute", "getTaxiRoute",
                        "getTrainSchedule", "getBusSchedule", "getFlightSchedule", "getCurrentPlan");
    }

    @Test
    void sendMessageAlwaysRegistersScheduleTools() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("서울에서 부산 기차 몇시에 있어?"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("서울에서 부산 기차 몇시에 있어?", null, null, null));

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
                1L, 1L, new ChatbotReqDTO.SendMessage("안녕", null, null, null));

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
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천해줘", null, null, null));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).placeId()).isEqualTo("77");
        assertThat(result.candidates().get(0).category()).isEqualTo(BlockCategory.FOOD);
    }

    @Test
    @DisplayName("응답이 언급한 후보만 candidates에 남는다 — CandidateSelector 배선이 빠지면 tool이 모은 후보가 그대로 다 나가 이 테스트가 깨진다")
    void onlyMentionedCandidateSurvivesInResponse() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").budgetHeadcount(4).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        PlaceResDTO.Place starbucks = PlaceResDTO.Place.builder()
                .placeId("77").name("스타벅스").address("제주 서귀포시")
                .lat(33.45).lng(126.93).category("카페")
                .build();
        PlaceResDTO.Place ediya = PlaceResDTO.Place.builder()
                .placeId("88").name("이디야").address("제주 서귀포시")
                .lat(33.46).lng(126.94).category("카페")
                .build();
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenReturn(List.of(starbucks, ediya));
        // 실제 tool-calling 루프 대신, 모델이 tool을 부른 상황을 tool 직접 호출로 재현한다
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            options.getToolCallbacks().stream()
                    .filter(tc -> tc.getToolDefinition().name().equals("searchPlaces"))
                    .findFirst()
                    .orElseThrow()
                    .call("{\"keyword\":\"카페\"}");
            return canned("스타벅스 추천드려요");
        });

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천해줘", null, null, null));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).name()).isEqualTo("스타벅스");
    }

    @Test
    @DisplayName("봇 응답의 후보가 이력의 assistant 턴에 저장된다")
    void 후보가_이력에_저장된다() {
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

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천해줘", null, null, null));

        ArgumentCaptor<ChatTurn> assistant = ArgumentCaptor.forClass(ChatTurn.class);
        verify(chatHistoryStore).appendExchange(eq(1L), eq(1L), any(ChatTurn.class), assistant.capture());
        assertThat(assistant.getValue().role()).isEqualTo(ChatTurn.ROLE_ASSISTANT);
        assertThat(assistant.getValue().candidates()).hasSize(1);
        assertThat(assistant.getValue().candidates().get(0).placeId()).isEqualTo("77");
    }

    /**
     * 주입된 블록의 본문. 라벨("[Map view]")만으로는 부족하다 — MAP_TAIL 규칙 문장이
     * 그 라벨을 가리키느라 같은 글자를 이미 담고 있어서, 블록이 빠져도 라벨은 남는다.
     */
    private static final String MAP_VIEW_LINE = "지금 사용자가 보고 있는 지도 범위: ";

    private static final ChatbotReqDTO.MapContext VIEWPORT =
            new ChatbotReqDTO.MapContext(33.44, 126.93, 33.47, 126.95);

    @Test
    @DisplayName("MAP 모드인데 뷰포트가 없으면 거절한다 — 조건부 필수라 애노테이션으로 못 걸린다")
    void mapModeWithoutViewportIsRejected() {
        assertThatThrownBy(() -> chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, null, null)))
                .isInstanceOf(ChatbotException.class);
    }

    @Test
    @DisplayName("MAP 모드는 뷰포트 장소검색 tool만 등록한다 — 축제·경로·시간표는 지도 추천과 무관하고 tool이 적을수록 선택이 정확하다")
    void mapModeRegistersOnlyViewportTool() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder()
                .destination("제주도")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .budgetHeadcount(4)
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("이 근처 카페예요"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactly("searchPlacesInView");
    }

    @Test
    @DisplayName("MAP 모드는 지도 범위 밖을 추천하지 말라는 지시를 프롬프트에 붙인다")
    void mapModeAppendsViewportInstructionToPrompt() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String systemText = promptCaptor.getValue().getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
        assertThat(systemText).contains("지도 기반 추천 모드");
    }

    @Test
    @DisplayName("MAP 모드는 뷰포트 중심의 지역명을 프롬프트에 주입한다 — 모델이 지역을 부를 이름이 없으면 이력에서 끌어온다")
    void mapModeInjectsReverseGeocodedRegionName() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(placeQueryService.reverseGeocode((33.44 + 33.47) / 2, (126.93 + 126.95) / 2))
                .thenReturn(Optional.of(new PlaceResDTO.Address("부산 해운대구 우동 1394", "부산 해운대구 해운대해변로 264")));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, null));

        assertThat(capturedSystemText()).contains("[Map view]")
                .contains(MAP_VIEW_LINE + "부산 해운대구 우동 일대")
                // 번지까지 읽어 주면 지도 범위가 아니라 그 한 필지를 가리키는 말이 된다
                .doesNotContain("1394");
    }

    @Test
    @DisplayName("산번지도 떼어낸다 — '산 12-3' 같은 꼴은 숫자만 지우면 '산'이 지역명 끝에 남는다")
    void mapViewStripsMountainLotNumberToo() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(placeQueryService.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new PlaceResDTO.Address("부산 기장군 일광읍 산 12-3", "부산 기장군 일광로 1")));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, null));

        assertThat(capturedSystemText()).contains(MAP_VIEW_LINE + "부산 기장군 일광읍 일대");
    }

    @Test
    @DisplayName("역지오코딩 결과가 없으면 [Map view] 블록을 아예 넣지 않는다 — 빈 지역명을 말하게 두는 것보다 낫다")
    void mapModeOmitsMapViewBlockWhenRegionUnresolved() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(placeQueryService.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, null));

        assertThat(result.reply()).isEqualTo("답변");
        assertThat(capturedSystemText()).doesNotContain(MAP_VIEW_LINE);
    }

    @Test
    @DisplayName("역지오코딩이 터져도 채팅은 계속된다 — 장식용 정보 하나가 턴 전체를 죽이면 안 된다")
    void mapModeSurvivesReverseGeocodeFailure() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(placeQueryService.reverseGeocode(anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("카카오 500"));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, null));

        assertThat(result.reply()).isEqualTo("답변");
        assertThat(capturedSystemText()).doesNotContain(MAP_VIEW_LINE);
    }

    @Test
    @DisplayName("GENERAL 모드는 역지오코딩을 아예 호출하지 않는다 — 카카오 호출이 일반 채팅마다 늘면 안 된다")
    void generalModeNeverReverseGeocodes() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.GENERAL, VIEWPORT, null));

        verify(placeQueryService, never()).reverseGeocode(anyDouble(), anyDouble());
        assertThat(capturedSystemText()).doesNotContain(MAP_VIEW_LINE);
    }

    /**
     * 주입된 [Viewing] 블록의 본문. 라벨만 pin 하면 SYSTEM 의 다중 후보 규칙이 같은 글자를
     * 이미 담고 있어서, 블록이 통째로 빠져도 통과한다 — [Map view] 와 같은 이유다.
     */
    private static final String VIEWING_LINE = "사용자가 지금 보고 있는 Day: ";

    @Test
    @DisplayName("GENERAL 모드도 보고 있는 Day 를 프롬프트에 주입한다 — 점심 블록이 둘일 때 되묻지 않게 하는 데이터다")
    void generalModeInjectsViewingDay() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("점심 먹은 데 근처에 카페 있어?", ChatbotMode.GENERAL, null, 2));

        assertThat(capturedSystemText()).contains("\n[Viewing]\n").contains(VIEWING_LINE + "2");
    }

    @Test
    @DisplayName("MAP 모드에서도 보고 있는 Day 가 실린다 — 모드와 무관한 정보다")
    void mapModeInjectsViewingDayToo() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.MAP, VIEWPORT, 3));

        assertThat(capturedSystemText()).contains("\n[Viewing]\n").contains(VIEWING_LINE + "3");
    }

    @Test
    @DisplayName("Day 를 안 보내면 [Viewing] 블록 없이 그대로 답한다 — 구 클라이언트·음성 경로가 깨지면 안 된다")
    void omitsViewingBlockWhenDayNoAbsent() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("점심 먹은 데 근처에 카페 있어?", ChatbotMode.GENERAL, null, null));

        assertThat(result.reply()).isEqualTo("답변");
        // 라벨 자체는 SYSTEM 의 다중 후보 규칙이 이미 가리키고 있다 — 빠졌는지는 본문으로만 알 수 있다
        assertThat(capturedSystemText()).doesNotContain(VIEWING_LINE);
    }

    /** 조립된 시스템 메시지를 꺼낸다 — 프롬프트 주입 검증이 전부 이 경로를 쓴다 */
    private String capturedSystemText() {
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        return promptCaptor.getValue().getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("GENERAL 모드에서는 뷰포트를 보내도 무시한다 — 일반 채팅 tool 구성이 그대로다")
    void generalModeIgnoresViewport() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").budgetHeadcount(4).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페 추천", ChatbotMode.GENERAL, VIEWPORT, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .contains("searchPlaces")
                .doesNotContain("searchPlacesInView");
    }

    @Test
    @DisplayName("GENERAL 모드는 보드 조회 tool을 등록한다 — 일정을 참조하는 질문에 답할 수 있어야 한다")
    void generalModeRegistersBoardTool() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder().destination("제주도").budgetHeadcount(4).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("내 일정 어때?", null, null, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .contains("getCurrentPlan");
    }

    @Test
    @DisplayName("MAP 모드는 보드 tool을 등록하지 않는다 — 뷰포트 장소검색 하나만 남긴다")
    void mapModeDoesNotRegisterBoardTool() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("카페", ChatbotMode.MAP, VIEWPORT, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .doesNotContain("getCurrentPlan");
    }

    @Test
    @DisplayName("확정 일정이 재정렬 기준이 된다 — 좌표 없는 블록은 앵커에서 빠진다")
    void rankingContextTakesAnchorsFromBlocksWithCoordinates() {
        PlaceRanker.RankingContext context = chatbotCommandService.buildRankingContext(
                VIEWPORT,
                new RequestScopedBoard(() -> List.of(
                        block(BlockCategory.FOOD, "33.4500", "126.9400"),
                        block(BlockCategory.ETC, null, null))));

        // 좌표 없는 블록이 앵커가 되면 적도 앞바다(0,0)가 기준점이 되어 거리 신호가 통째로 망가진다
        assertThat(context.boardAnchors()).hasSize(1);
        assertThat(context.boardAnchors().get(0).lat()).isEqualTo(33.45);
        assertThat(context.viewportCenter().lat()).isEqualTo((33.44 + 33.47) / 2);
    }

    @Test
    @DisplayName("앵커 이름은 블록 이름에서 그대로 가져온다 — 추천 이유가 실제 앵커를 부를 수 있어야 한다")
    void rankingContextAnchorNameComesFromBlockName() {
        PlaceRanker.RankingContext context = chatbotCommandService.buildRankingContext(
                VIEWPORT,
                new RequestScopedBoard(() -> List.of(
                        block(BlockCategory.FOOD, "33.4500", "126.9400", "해운대암소갈비집"))));

        assertThat(context.boardAnchors().get(0).name()).isEqualTo("해운대암소갈비집");
    }

    @Test
    @DisplayName("이름 없는 블록도 앵커로는 쓰인다 — 이름만 비어 있을 뿐이다")
    void rankingContextAnchorIsUnnamedWhenBlockNameIsBlank() {
        PlaceRanker.RankingContext context = chatbotCommandService.buildRankingContext(
                VIEWPORT,
                new RequestScopedBoard(() -> List.of(
                        block(BlockCategory.FOOD, "33.4500", "126.9400", null))));

        assertThat(context.boardAnchors()).hasSize(1);
        assertThat(context.boardAnchors().get(0).name()).isNull();
    }

    @Test
    @DisplayName("보드에 있는 종류의 장소는 실제로 뒤로 밀린다 — 표기가 어긋나면 중복 페널티가 영영 안 걸린다")
    void plannedCategoriesActuallyPenalizeSameKindPlaces() {
        // 보드에는 BlockCategory(FOOD)만 있고, 카카오는 한글 그룹명("카페")만 준다.
        // 이 둘을 그대로 비교하면 한 번도 안 걸리므로, 배선이 실제로 맞물리는지를 여기서 본다.
        PlaceRanker.RankingContext context = chatbotCommandService.buildRankingContext(
                VIEWPORT,
                new RequestScopedBoard(() -> List.of(
                        block(BlockCategory.FOOD, "33.4550", "126.9400"),
                        block(BlockCategory.FOOD, "33.4550", "126.9400"))));

        PlaceResDTO.Place cafe = PlaceResDTO.Place.builder()
                .placeId("1").name("가카페").address("제주")
                .lat(33.4550).lng(126.9400).category("카페").build();
        PlaceResDTO.Place viewpoint = PlaceResDTO.Place.builder()
                .placeId("2").name("나전망대").address("제주")
                .lat(33.4550).lng(126.9400).category("관광명소").build();

        assertThat(PlaceRanker.rank(List.of(cafe, viewpoint), context))
                .extracting(PlaceResDTO.Place::name)
                .containsExactly("나전망대", "가카페");
    }

    private static Block block(BlockCategory category, String lat, String lng) {
        return block(category, lat, lng, "블록");
    }

    private static Block block(BlockCategory category, String lat, String lng, String name) {
        return Block.builder()
                .category(category)
                .name(name)
                .orderKey("a0")
                .lat(lat == null ? null : new BigDecimal(lat))
                .lng(lng == null ? null : new BigDecimal(lng))
                .source(BlockSource.MANUAL)
                .build();
    }

    @Test
    @DisplayName("목표 예산이 프롬프트에 주입된다 — 보드에서 지출 합계를 알아도 비교 대상이 없으면 반쪽이다")
    void targetBudgetIsInjectedIntoPrompt() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        Project project = Project.builder()
                .destination("제주도").budgetHeadcount(4).targetBudget(300000)
                .build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("예산 어때?", null, null, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String systemText = promptCaptor.getValue().getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElseThrow();
        assertThat(systemText).contains("targetBudget: 300000");
    }
}
