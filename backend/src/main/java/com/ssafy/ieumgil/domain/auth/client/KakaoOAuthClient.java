package com.ssafy.ieumgil.domain.auth.client;

import com.ssafy.ieumgil.domain.auth.dto.KakaoTokenResponse;
import com.ssafy.ieumgil.domain.auth.dto.KakaoUserInfoResponse;
import com.ssafy.ieumgil.domain.auth.exception.AuthErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private final KakaoOAuthProperties properties;
    private final RestClient restClient = RestClient.create();

    /**
     * 프론트에서 전달받은 인가코드로 카카오 액세스 토큰을 발급받는다.
     *
     * 실패를 4xx와 그 외로 나눈다 — 인가코드 만료·재사용(invalid_grant)은 재로그인하면
     * 풀리는 클라이언트 상황이라 401이어야 하고, 502로 내보내면 프론트가 "잠시 후 재시도"로
     * 안내해 사용자가 계속 막힌다. 게다가 5xx는 GlobalExceptionHandler가 스택트레이스까지
     * error 로 남기므로, 흔한 사용자 행동이 서버 에러 알람이 된다.
     */
    public KakaoTokenResponse requestToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", code);
        if (StringUtils.hasText(properties.clientSecret())) {
            form.add("client_secret", properties.clientSecret());
        }

        try {
            return restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
        } catch (HttpClientErrorException e) {
            // 4xx 는 대개 invalid_grant(만료·재사용)지만, invalid_client 나 redirect_uri
            // 불일치 같은 우리 설정 오류도 같은 4xx 로 온다. 응답 본문을 남겨 로그에서
            // 구분할 수 있게 한다 — 설정 오류라면 모든 요청이 실패하므로 양으로 드러난다.
            log.warn("카카오 토큰 발급 거부(4xx): {}", e.getResponseBodyAsString());
            throw new CustomException(AuthErrorCode.KAKAO_AUTH_FAILED);
        } catch (RestClientException e) {
            // 5xx·연결 실패·타임아웃 — 카카오 쪽 장애
            log.warn("카카오 토큰 발급 실패: {}", e.getMessage());
            throw new CustomException(AuthErrorCode.KAKAO_TOKEN_FAILED);
        }
    }

    /**
     * 카카오 액세스 토큰으로 사용자 정보를 조회한다.
     *
     * requestToken 과 달리 4xx 를 갈라내지 않는다 — 방금 우리가 발급받은 토큰으로 조회하는데
     * 거부당했다면 사용자가 다시 로그인해도 풀리지 않는다. 우리 흐름이나 카카오 쪽 문제이므로
     * 502 로 두는 것이 맞다.
     */
    public KakaoUserInfoResponse requestUserInfo(String kakaoAccessToken) {
        try {
            return restClient.get()
                    .uri(properties.userInfoUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
        } catch (RestClientException e) {
            log.warn("카카오 사용자 정보 조회 실패: {}", e.getMessage());
            throw new CustomException(AuthErrorCode.KAKAO_USER_INFO_FAILED);
        }
    }
}
