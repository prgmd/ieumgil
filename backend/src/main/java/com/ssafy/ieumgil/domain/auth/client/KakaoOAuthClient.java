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
        } catch (RestClientException e) {
            log.warn("카카오 토큰 발급 실패: {}", e.getMessage());
            throw new CustomException(AuthErrorCode.KAKAO_TOKEN_FAILED);
        }
    }

    /**
     * 카카오 액세스 토큰으로 사용자 정보를 조회한다.
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
