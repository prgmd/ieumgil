package com.ssafy.ieumgil.domain.chatbot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebSearchInterceptor}의 web_search 주입 판정을 검증한다. GENERAL 요청(다중 tool)에는
 * web_search를 주입하고, MAP 요청(뷰포트 tool 하나만)에는 주입하지 않아 추천 카드 경로를 지킨다.
 * 실모델 호출 없이 나가는 wire body만 sniff하므로 비-live 단위 테스트다.
 */
class WebSearchInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEB_SEARCH_TYPE = "web_search_20250305";

    private final WebSearchInterceptor interceptor = new WebSearchInterceptor();

    @Test
    @DisplayName("GENERAL 요청(다중 tool)에는 web_search를 주입한다")
    void injectsWebSearchForGeneralRequest() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlaces"},{"name":"getBoard"}]}
                """);

        assertThat(hasWebSearch(outgoing)).isTrue();
    }

    @Test
    @DisplayName("MAP 요청(뷰포트 tool 하나만)에는 web_search를 주입하지 않는다")
    void skipsWebSearchForMapRequest() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}]}
                """);

        assertThat(hasWebSearch(outgoing)).isFalse();
        assertThat(outgoing.get("tools")).hasSize(1);
    }

    @Test
    @DisplayName("tool이 없는 요청에는 기존대로 web_search를 주입한다")
    void injectsWebSearchWhenNoTools() throws IOException {
        JsonNode outgoing = outgoingBody("{}");

        assertThat(hasWebSearch(outgoing)).isTrue();
    }

    /** interceptor를 통과시키고 실제로 나가는 wire body(JSON)를 캡처해 돌려준다. */
    private JsonNode outgoingBody(String requestBody) throws IOException {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://gms.ssafy.io/gmsapi/api.anthropic.com/v1/messages"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getBody()).thenReturn(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(eq(request), captor.capture())).thenReturn(response);

        interceptor.intercept(request, requestBody.getBytes(StandardCharsets.UTF_8), execution);

        return MAPPER.readTree(captor.getValue());
    }

    private boolean hasWebSearch(JsonNode outgoing) {
        JsonNode tools = outgoing.get("tools");
        if (tools == null || !tools.isArray()) {
            return false;
        }
        for (JsonNode tool : tools) {
            if (WEB_SEARCH_TYPE.equals(tool.path("type").asText(""))) {
                return true;
            }
        }
        return false;
    }
}
