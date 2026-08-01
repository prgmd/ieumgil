package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
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
            날씨·환율·실시간 정보처럼 확인할 수 없는 것은 모른다고 말하고 대안을 안내하세요.
            요청이 목적지·기간 등 핵심 정보 없이 지나치게 모호할 때만 필요한 정보를 되물으세요.
            답변은 핵심만 간결하게 전하고, 불필요하게 길게 나열하지 마세요.
            """;

    private final ChatModel chatModel;
    private final ChatHistoryStore chatHistoryStore;
    private final ProjectRepository projectRepository;
    private final FestivalQueryService festivalQueryService;
    private final PlaceQueryService placeQueryService;
    private final TrainScheduleTool trainScheduleTool;
    private final BusScheduleTool busScheduleTool;
    private final FlightScheduleTool flightScheduleTool;

    @Override
    public ChatbotResDTO.MessageResult sendMessage(Long projectId, Long memberId, ChatbotReqDTO.SendMessage request) {
        List<ChatTurn> history = chatHistoryStore.loadHistory(projectId, memberId);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChatTurn turn : history) {
            messages.add(toMessage(turn));
        }
        messages.add(new UserMessage(request.message()));

        Optional<FestivalRecommendationTool> festivalTool = resolveFestivalTool(projectId);
        List<Object> kakaoTools = resolveKakaoTools(projectId);
        String reply = callGms(messages, festivalTool, kakaoTools);

        chatHistoryStore.appendExchange(
                projectId,
                memberId,
                new ChatTurn(ChatTurn.ROLE_USER, request.message()),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, reply)
        );

        return ChatbotResDTO.MessageResult.builder()
                .reply(reply)
                .build();
    }

    /** 프로젝트 목적지·기간이 지역코드로 해석 가능할 때만 축제 추천 툴을 만든다. 실패해도 예외 없이 빈 Optional. */
    Optional<FestivalRecommendationTool> resolveFestivalTool(Long projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .filter(project -> project.getStartDate() != null && project.getEndDate() != null)
                .flatMap(project -> RegionCode.findByName(project.getDestination())
                        .map(regionCode -> new FestivalRecommendationTool(
                                regionCode, project.getStartDate(), project.getEndDate(), festivalQueryService
                        )));
    }

    /** 프로젝트 목적지가 있을 때만 카카오 tool 3개(장소검색/도보/택시)를 만든다. 목적지 없으면 빈 리스트. */
    List<Object> resolveKakaoTools(Long projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .map(Project::getDestination)
                .filter(destination -> destination != null && !destination.isBlank())
                .map(destination -> {
                    KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService);
                    return List.<Object>of(
                            new KakaoPlaceSearchTool(destination, placeQueryService),
                            new WalkingRouteTool(destination, placeQueryService, resolver),
                            new TaxiRouteTool(destination, placeQueryService, resolver)
                    );
                })
                .orElseGet(List::of);
    }

    private String callGms(List<Message> messages, Optional<FestivalRecommendationTool> festivalTool, List<Object> kakaoTools) {
        try {
            ChatClient.ChatClientRequestSpec spec = ChatClient.builder(chatModel).build()
                    .prompt(new Prompt(messages));
            List<Object> tools = new ArrayList<>(kakaoTools);
            tools.add(trainScheduleTool);
            tools.add(busScheduleTool);
            tools.add(flightScheduleTool);
            festivalTool.ifPresent(tools::add);
            spec = spec.tools(tools.toArray());
            return spec.call().content();
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
