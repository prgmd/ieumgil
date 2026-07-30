package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
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

    @Override
    public ChatbotResDTO.MessageResult sendMessage(Long projectId, Long memberId, ChatbotReqDTO.SendMessage request) {
        List<ChatTurn> history = chatHistoryStore.loadHistory(projectId, memberId);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChatTurn turn : history) {
            messages.add(toMessage(turn));
        }
        messages.add(new UserMessage(request.message()));

        String reply = callGms(messages);

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

    private String callGms(List<Message> messages) {
        try {
            return ChatClient.builder(chatModel).build()
                    .prompt(new Prompt(messages))
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
