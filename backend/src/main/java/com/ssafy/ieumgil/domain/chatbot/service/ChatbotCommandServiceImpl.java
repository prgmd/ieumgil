package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import com.ssafy.ieumgil.domain.chatbot.tool.ViewportPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.festival.RegionCode;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatbotCommandServiceImpl implements ChatbotCommandService {

    private static final String SYSTEM_PROMPT = """
            당신은 '이음길' 여행 일정 플래너 앱의 챗봇 캐릭터 '이음이'입니다.
            사용자가 여행 일정을 세우도록 돕는 친근한 도우미로, 장소·맛집·볼거리·이동·축제 등
            여행 전반의 질문을 폭넓게 도와줍니다. 특정 기능에만 자신을 한정하지 마세요.
            답할 수 있는 질문에는 사과하거나 되묻기부터 하지 말고, 먼저 도움이 되는 답을 준 뒤
            필요하면 구체화를 위한 질문을 덧붙이세요.
            도구(검색·경로·시간표 등)가 준 정보만 사용하고, 그 밖의 사실·날짜·기간을 지어내거나
            넘겨짚어 부풀리지 마세요. 검색 결과가 비었거나 빈약하면 억지로 추천을 만들지 말고
            솔직히 알린 뒤 더 구체적인 조건을 물어보세요.
            가게 영업 여부·폐업·리뷰·최신 상태처럼 확인이 필요한 정보는 웹 검색으로 확인해 답하세요.
            반면 날씨·환율처럼 검색으로도 신뢰하기 어려운 실시간 값은 모른다고 말하고 대안을 안내하세요.
            요청이 목적지·기간 등 핵심 정보 없이 지나치게 모호할 때만 필요한 정보를 되물으세요.
            답변은 핵심만 간결하게 전하고, 불필요하게 길게 나열하지 마세요.
            아래 [Current trip]은 서버가 제공한 이 여행의 사실 정보입니다. 신뢰해서 활용하고,
            거기 (unset)으로 표시된 값은 아직 정해지지 않은 것이니 지어내지 말고 필요하면 물어보세요.
            """;

    private final ChatModel chatModel;
    private final ChatHistoryStore chatHistoryStore;
    private final ProjectRepository projectRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final FestivalQueryService festivalQueryService;
    private final PlaceQueryService placeQueryService;
    private final TrainScheduleTool trainScheduleTool;
    private final BusScheduleTool busScheduleTool;
    private final FlightScheduleTool flightScheduleTool;

    @Override
    public ChatbotResDTO.MessageResult sendMessage(Long projectId, Long memberId, ChatbotReqDTO.SendMessage request) {
        ChatbotMode mode = request.modeOrDefault();
        // 조건부 필수라 애노테이션으로 못 걸린다 — 블록 생성의 좌표 교차 검증과 같은 패턴
        if (mode == ChatbotMode.MAP && request.mapContext() == null) {
            throw new ChatbotException(ChatbotErrorCode.MAP_CONTEXT_REQUIRED);
        }

        List<ChatTurn> history = chatHistoryStore.loadHistory(projectId, memberId);

        // 메타데이터 주입과 tool 구성이 같은 로드를 공유한다 — 예전엔 resolver 둘이 각자 조회해 쿼리가 두 번 나갔다
        Optional<Project> project = projectRepository.findByIdAndDeletedAtIsNull(projectId);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT + modePrompt(mode) + buildTripContext(project)));
        for (ChatTurn turn : history) {
            messages.add(toMessage(turn));
        }
        messages.add(new UserMessage(request.message()));

        CandidateCollector candidateCollector = new CandidateCollector();
        String reply = callGms(messages, resolveTools(mode, request, project, candidateCollector));

        chatHistoryStore.appendExchange(
                projectId,
                memberId,
                new ChatTurn(ChatTurn.ROLE_USER, request.message()),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, reply)
        );

        return ChatbotResDTO.MessageResult.builder()
                .reply(reply)
                .candidates(candidateCollector.candidates())
                .build();
    }

    /**
     * 모드별 프롬프트 꼬리. 지도 모드에서 범위 밖 장소를 끼워넣지 않도록 못을 박는다.
     * 마지막 문장이 중요하다 — 좁은 범위에서 결과가 0건일 때 억지로 지어내는 것을 막는다.
     */
    private String modePrompt(ChatbotMode mode) {
        if (mode != ChatbotMode.MAP) {
            return "";
        }
        return """
                지금은 지도 기반 추천 모드입니다. 사용자가 보고 있는 지도 범위 안의 장소만 추천하세요.
                그 범위 밖 장소나 학습 지식으로 아는 장소를 끼워넣지 마세요.
                범위 안에 마땅한 결과가 없으면 없다고 알리고 지도를 옮기거나 넓혀 보라고 안내하세요.
                """;
    }

    /**
     * 여행 메타데이터를 시스템 프롬프트 꼬리로 만든다 (BOT-03 컨텍스트).
     * 프로젝트를 못 찾으면 빈 문자열 — 채팅 자체는 계속 되어야 한다.
     */
    private String buildTripContext(Optional<Project> project) {
        return project
                .map(p -> TripContextBuilder.build(p, resolveHeadcount(p)))
                .orElse("");
    }

    /**
     * 정산 인원(BGT-03). budgetHeadcount가 지정돼 있으면 그 값을 쓰고, null이면 그룹 멤버 수로 폴백한다.
     * 지정돼 있을 때 count 쿼리를 아예 안 나가게 하는 것이 의도다.
     */
    private Integer resolveHeadcount(Project project) {
        if (project.getBudgetHeadcount() != null) {
            return project.getBudgetHeadcount();
        }
        if (project.getTravelGroup() == null || project.getTravelGroup().getId() == null) {
            return null;
        }
        return (int) groupMemberRepository.countMembers(project.getTravelGroup().getId());
    }

    /** 프로젝트 목적지·기간이 지역코드로 해석 가능할 때만 축제 추천 툴을 만든다. 실패해도 예외 없이 빈 Optional. */
    Optional<FestivalRecommendationTool> resolveFestivalTool(Optional<Project> loadedProject,
                                                            CandidateCollector candidateCollector) {
        return loadedProject
                .filter(project -> project.getStartDate() != null && project.getEndDate() != null)
                .flatMap(project -> RegionCode.findByName(project.getDestination())
                        .map(regionCode -> new FestivalRecommendationTool(
                                regionCode, project.getStartDate(), project.getEndDate(),
                                festivalQueryService, candidateCollector
                        )));
    }

    /** 프로젝트 목적지가 있을 때만 카카오 tool 3개(장소검색/도보/택시)를 만든다. 목적지 없으면 빈 리스트. */
    List<Object> resolveKakaoTools(Optional<Project> loadedProject, CandidateCollector candidateCollector) {
        return loadedProject
                .map(Project::getDestination)
                .filter(destination -> destination != null && !destination.isBlank())
                .map(destination -> {
                    KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService);
                    return List.<Object>of(
                            new KakaoPlaceSearchTool(destination, placeQueryService, candidateCollector),
                            new WalkingRouteTool(destination, placeQueryService, resolver),
                            new TaxiRouteTool(destination, placeQueryService, resolver)
                    );
                })
                .orElseGet(List::of);
    }

    /**
     * 모드별 tool 구성.
     *
     * <p>MAP 모드는 뷰포트 장소검색 하나만 등록한다. 축제(BOT-05는 일반 채팅 전용)·경로·시간표는
     * "지도에 보이는 범위에서 장소를 고른다"는 흐름과 무관하고, 노출 tool이 적을수록
     * 모델의 선택 정확도가 올라간다.
     */
    private List<Object> resolveTools(ChatbotMode mode, ChatbotReqDTO.SendMessage request,
                                      Optional<Project> project, CandidateCollector candidateCollector) {
        if (mode == ChatbotMode.MAP) {
            return List.of(new ViewportPlaceSearchTool(request.mapContext(), placeQueryService, candidateCollector));
        }

        List<Object> tools = new ArrayList<>(resolveKakaoTools(project, candidateCollector));
        tools.add(trainScheduleTool);
        tools.add(busScheduleTool);
        tools.add(flightScheduleTool);
        resolveFestivalTool(project, candidateCollector).ifPresent(tools::add);
        return tools;
    }

    private String callGms(List<Message> messages, List<Object> tools) {
        try {
            return ChatClient.builder(chatModel).build()
                    .prompt(new Prompt(messages))
                    .tools(tools.toArray())
                    .call()
                    .content();
        } catch (RuntimeException e) {
            throw new ChatbotException(ChatbotErrorCode.GMS_CALL_FAILED);
        }
    }

    private Message toMessage(ChatTurn turn) {
        if (ChatTurn.ROLE_ASSISTANT.equals(turn.role())) {
            return new AssistantMessage(turn.content());
        }
        return new UserMessage(turn.content());
    }
}
