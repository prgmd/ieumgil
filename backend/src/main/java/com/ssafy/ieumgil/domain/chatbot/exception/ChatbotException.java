package com.ssafy.ieumgil.domain.chatbot.exception;

import com.ssafy.ieumgil.global.exception.CustomException;

public class ChatbotException extends CustomException {

    public ChatbotException(ChatbotErrorCode code) {
        super(code);
    }
}
