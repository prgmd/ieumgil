package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import com.ssafy.ieumgil.domain.chatbot.tool.ViewportPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.chatbot.tool.BoardTool;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateSelector;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.PlaceRanker;
import com.ssafy.ieumgil.domain.chatbot.tool.RequestScopedBoard;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
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
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatbotCommandServiceImpl implements ChatbotCommandService {

    /** 지번 주소 끝의 번지("… 1394", "… 산 12-3"). 앞의 행정구역만 남기려고 떼어낸다 */
    private static final Pattern BUNJI_SUFFIX = Pattern.compile("\\s+(산\\s*)?\\d+(-\\d+)?$");

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
        // 지역명 주입은 MAP 모드에서만 — GENERAL은 역지오코딩 호출 자체가 나가지 않는다
        String mapViewContext = mode == ChatbotMode.MAP ? buildMapViewContext(request.mapContext()) : "";
        messages.add(new SystemMessage(ChatbotPrompt.SYSTEM + ChatbotPrompt.modeTail(mode)
                + buildTripContext(project) + mapViewContext + buildViewingContext(request.dayNo())));
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
        List<ChatbotResDTO.Candidate> candidates =
                CandidateSelector.mentionedIn(reply, candidateCollector.candidates());

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
     * 지금 보고 있는 지도 범위의 지역명을 시스템 프롬프트 꼬리로 만든다 (MAP 모드 전용).
     *
     * <p>서버가 모델에 주는 것이 좌표뿐이라, 모델이 지역을 <b>부를 이름</b>이 없어서 대화 이력에서
     * 가장 최근에 나온 지역명을 끌어다 썼다(뷰포트는 해운대인데 "국제시장 근처에서…"). 프롬프트 규칙만으로는
     * 검색 행동은 고쳐졌어도 문장의 지역명은 그대로였다 — 말할 이름을 데이터로 준다.
     *
     * <p>실패는 전부 삼킨다. 이건 문장을 다듬는 장식용 정보이므로,
     * 카카오 호출 한 번이 더 늘었다고 채팅 턴 전체가 죽어서는 안 된다.
     */
    private String buildMapViewContext(ChatbotReqDTO.MapContext viewport) {
        PlaceRanker.Anchor center = viewportCenter(viewport);
        try {
            return placeQueryService.reverseGeocode(center.lat(), center.lng())
                    .map(PlaceResDTO.Address::address)
                    .map(ChatbotCommandServiceImpl::stripBunji)
                    .filter(region -> !region.isBlank())
                    .map("\n[Map view]\n지금 사용자가 보고 있는 지도 범위: %s 일대\n"::formatted)
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("뷰포트 역지오코딩 실패 — [Map view] 없이 진행한다", e);
            return "";
        }
    }

    /**
     * 사용자가 지금 보고 있는 Day를 시스템 프롬프트 꼬리로 만든다 (두 모드 공통).
     *
     * <p>"점심 먹은 데 근처에 카페 있어?"에서 1박2일 보드의 점심 블록이 둘이라 모델이
     * "어느 날이신가요?"로 되물어 카드가 0건이 났다. 프롬프트로 "가장 이른 날"을 고르게 해
     * 막았지만 그건 어디까지나 짐작이다 — 사용자가 보고 있는 Day 탭이 곧 그 "점심"이므로
     * 짐작 대신 데이터로 준다.
     *
     * <p>{@code dayNo}가 없으면 블록을 통째로 뺀다. 구 클라이언트·음성 경로는 안 보내고,
     * 그때는 프롬프트의 "가장 이른 날" 폴백이 그대로 돈다.
     */
    private String buildViewingContext(Integer dayNo) {
        return dayNo == null ? "" : "\n[Viewing]\n사용자가 지금 보고 있는 Day: %d\n".formatted(dayNo);
    }

    /**
     * 지번 주소에서 끝의 번지를 떼어 행정구역만 남긴다.
     * "부산 해운대구 우동 1394" → "부산 해운대구 우동", "부산 기장군 일광읍 산 12-3" → "부산 기장군 일광읍".
     * 번지까지 읽어 주면 지도 범위가 아니라 그 한 필지를 가리키는 말이 된다.
     */
    private static String stripBunji(String jibunAddress) {
        return BUNJI_SUFFIX.matcher(jibunAddress).replaceAll("").trim();
    }

    /** 뷰포트 중심. 랭킹 기준점과 지역명 조회가 같은 점을 봐야 해서 한 곳에서만 계산한다. */
    private static PlaceRanker.Anchor viewportCenter(ChatbotReqDTO.MapContext viewport) {
        return new PlaceRanker.Anchor(
                (viewport.swLat() + viewport.neLat()) / 2,
                (viewport.swLng() + viewport.neLng()) / 2,
                null);
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

    /**
     * 프로젝트 목적지·기간이 있을 때 축제 추천 툴을 등록한다. 목적지가 어느 시/도에 속하는지는
     * 여기서 판정하지 않고(도쿄 같은 해외·시·읍면리도 등록됨), 호출 시점에 모델이 tool 인자 {@code region}으로 넘긴다.
     */
    Optional<FestivalRecommendationTool> resolveFestivalTool(Optional<Project> loadedProject,
                                                            CandidateCollector candidateCollector) {
        return loadedProject
                .filter(project -> project.getStartDate() != null && project.getEndDate() != null)
                .filter(project -> project.getDestination() != null && !project.getDestination().isBlank())
                .map(project -> new FestivalRecommendationTool(
                        project.getStartDate(), project.getEndDate(),
                        festivalQueryService, candidateCollector));
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
            return List.of(new ViewportPlaceSearchTool(
                    request.mapContext(), placeQueryService, candidateCollector,
                    buildRankingContext(request.mapContext(), board)));
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

    /**
     * 재정렬 기준을 만든다.
     *
     * <p>보드 조회는 {@link RequestScopedBoard}가 요청당 1회로 묶으므로, 여기서 호출해도
     * 다른 tool이 보드를 이미 봤다면 추가 쿼리가 나가지 않는다.
     *
     * <p>계획 카테고리는 {@link com.ssafy.ieumgil.domain.block.entity.BlockCategory} 이름으로 넘긴다 —
     * 랭커가 장소의 카카오 그룹명을 같은 표기로 접어서 비교한다. 좌표 없는 블록(ETC·교통 일부)은
     * 앵커에서 뺀다. null을 0으로 읽으면 적도 앞바다가 기준점이 되어 거리 신호가 통째로 망가진다.
     */
    PlaceRanker.RankingContext buildRankingContext(ChatbotReqDTO.MapContext viewport, RequestScopedBoard board) {
        List<Block> blocks = board.get();
        List<PlaceRanker.Anchor> anchors = blocks.stream()
                .filter(b -> b.getLat() != null && b.getLng() != null)
                .map(b -> new PlaceRanker.Anchor(b.getLat().doubleValue(), b.getLng().doubleValue(), b.getName()))
                .toList();
        List<String> plannedCategories = blocks.stream()
                .map(Block::getCategory)
                .filter(Objects::nonNull)
                .map(Enum::name)
                .toList();
        return new PlaceRanker.RankingContext(anchors, viewportCenter(viewport), plannedCategories);
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
