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
            사용자의 여행 계획을 도와주는 친근하고 간결한 도우미입니다.
            답변은 2~3문장 이내로 짧고 실용적으로 하세요.
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
