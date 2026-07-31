package com.ssafy.ieumgil.global.websocket;

import java.security.Principal;

/**
 * STOMP 세션의 사용자 식별자.
 *
 * name에 userId 문자열을 담는다 — convertAndSendToUser(name, ...)의 수신자 키가 되고
 * (Step 5 voice 개인 큐), 인터셉터·레지스트리가 세션의 주인을 아는 기준이 된다.
 */
public record StompPrincipal(Long userId) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
