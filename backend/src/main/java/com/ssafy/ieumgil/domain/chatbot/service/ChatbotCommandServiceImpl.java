package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import com.ssafy.ieumgil.domain.chatbot.tool.ViewportPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.chatbot.tool.BoardTool;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.RequestScopedBoard;
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
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatbotCommandServiceImpl implements ChatbotCommandService {

    private final ChatModel chatModel;
    private final ChatHistoryStore chatHistoryStore;
    private final ProjectRepository projectRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final BlockRepository blockRepository;
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
        messages.add(new SystemMessage(ChatbotPrompt.SYSTEM + ChatbotPrompt.modeTail(mode) + buildTripContext(project)));
        // LLM 컨텍스트는 마지막 6턴(=12개 원소)만. 저장은 10턴이지만 프롬프트를 키우면 GMS 비용이 는다.
        int llmContextElements = 12;
        List<ChatTurn> recent = history.size() > llmContextElements
                ? history.subList(history.size() - llmContextElements, history.size())
                : history;
        for (ChatTurn turn : recent) {
            messages.add(toMessage(turn));
        }
        messages.add(new UserMessage(request.message()));

        CandidateCollector candidateCollector = new CandidateCollector();
        // 보드를 쓰는 곳이 둘(보드 tool·좌표 리졸버)이라 공급자를 하나로 공유해 쿼리를 한 번으로 묶는다
        RequestScopedBoard board = new RequestScopedBoard(() -> blockRepository.findChain(projectId));
        String reply = callGms(messages, resolveTools(board, mode, request, project, candidateCollector));
        List<ChatbotResDTO.Candidate> candidates = candidateCollector.candidates();

        chatHistoryStore.appendExchange(
                projectId,
                memberId,
                new ChatTurn(ChatTurn.ROLE_USER, request.message()),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, reply, candidates)
        );

        return ChatbotResDTO.MessageResult.builder()
                .reply(reply)
                .candidates(candidates)
                .build();
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
    List<Object> resolveKakaoTools(RequestScopedBoard board, Optional<Project> loadedProject,
                                   CandidateCollector candidateCollector) {
        return loadedProject
                .map(Project::getDestination)
                .filter(destination -> destination != null && !destination.isBlank())
                .map(destination -> {
                    // 보드 우선 좌표 해석 — 지연 조회라 경로 tool이 실제로 불릴 때만 쿼리가 나간다
                    KakaoPlaceCoordinateResolver resolver =
                            new KakaoPlaceCoordinateResolver(placeQueryService, board);
                    return List.<Object>of(
                            new KakaoPlaceSearchTool(destination, placeQueryService, candidateCollector, resolver),
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
    private List<Object> resolveTools(RequestScopedBoard board, ChatbotMode mode,
                                      ChatbotReqDTO.SendMessage request,
                                      Optional<Project> project, CandidateCollector candidateCollector) {
        if (mode == ChatbotMode.MAP) {
            return List.of(new ViewportPlaceSearchTool(request.mapContext(), placeQueryService, candidateCollector));
        }

        List<Object> tools = new ArrayList<>(resolveKakaoTools(board, project, candidateCollector));
        // 보드 조회는 프로젝트가 있을 때만 의미가 있다 — 카카오·축제 tool과 같은 조건부 등록이다
        project.ifPresent(p -> tools.add(new BoardTool(board)));
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
            // 사용자에게 나가는 예외는 그대로 두되(응답 계약), 원인은 스택까지 남긴다 —
            // 401·429·응답 파싱 실패·tool 내부 예외가 전부 이 한 줄로 뭉개지면 운영 중 원인을 못 찾는다.
            log.error("GMS 호출 실패", e);
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
