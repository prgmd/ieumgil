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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebSearchInterceptor}의 web_search 주입과 MAP 첫 패스 tool_choice 강제를 검증한다.
 * 두 모드 모두 web_search를 주입하되 MAP 요청(뷰포트 tool 하나만)은 검색 예산(max_uses)을
 * 낮춰 왕복과 지연을 억제하고, tool_result가 아직 없는 첫 패스에 한해 뷰포트 tool 호출을 강제한다.
 * 실모델 호출 없이 나가는 wire body만 sniff하므로 비-live 단위 테스트다.
 */
class WebSearchInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEB_SEARCH_TYPE = "web_search_20250305";

    private final WebSearchInterceptor interceptor = new WebSearchInterceptor();

    @Test
    @DisplayName("GENERAL 요청(다중 tool)에는 web_search를 max_uses 2로 주입한다")
    void injectsWebSearchForGeneralRequest() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlaces"},{"name":"getBoard"}]}
                """);

        assertThat(hasWebSearch(outgoing)).isTrue();
        // GENERAL 예산은 이 변경으로 건드리지 않는다 — 여기서 값을 고정해 회귀를 잡는다.
        assertThat(webSearch(outgoing).path("max_uses").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("MAP 요청(뷰포트 tool 하나만)에도 web_search를 주입하되 max_uses는 1로 낮춘다")
    void injectsWebSearchWithLowerBudgetForMapRequest() throws IOException {
        // 예전에는 MAP에서 스킵했다. 근거였던 "web_search를 노출하면 모델이 뷰포트 tool 대신
        // 산문으로 답해 카드가 사라진다"는 E2E에서 반증됐고(스킵 상태에서도 tool 미호출 발생),
        // 카드는 뷰포트 tool 결과로만 만들어지므로 웹 검색이 카드를 오염시키지 않는다.
        // 예산만 1로 낮춰 왕복 지연과 tool 결과에서 멀어질 여지를 줄인다.
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}]}
                """);

        assertThat(hasWebSearch(outgoing)).isTrue();
        assertThat(outgoing.get("tools")).hasSize(2);
        assertThat(webSearch(outgoing).path("max_uses").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("tool이 없는 요청에는 기존대로 web_search를 주입한다")
    void injectsWebSearchWhenNoTools() throws IOException {
        JsonNode outgoing = outgoingBody("{}");

        assertThat(hasWebSearch(outgoing)).isTrue();
    }

    @Test
    @DisplayName("GENERAL 요청: web_search 주입 후 tools 마지막 요소(=web_search)에 cache_control을 단다")
    void marksCacheControlOnLastToolForGeneralRequest() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlaces"},{"name":"getBoard"}]}
                """);

        JsonNode tools = outgoing.get("tools");
        JsonNode last = tools.get(tools.size() - 1);
        // 주입 순서상 마지막은 web_search이며, 여기에 캐시 브레이크포인트가 달려야 한다.
        assertThat(last.path("type").asText()).isEqualTo(WEB_SEARCH_TYPE);
        assertThat(last.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        // 앞선 custom tool에는 cache_control이 없다(브레이크포인트는 마지막 하나뿐).
        assertThat(tools.get(0).has("cache_control")).isFalse();
    }

    @Test
    @DisplayName("MAP 요청: 주입된 web_search(마지막)에 cache_control을 단다")
    void marksCacheControlOnLastToolForMapRequest() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}]}
                """);

        JsonNode tools = outgoing.get("tools");
        JsonNode last = tools.get(tools.size() - 1);
        assertThat(last.path("type").asText()).isEqualTo(WEB_SEARCH_TYPE);
        assertThat(last.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(tools.get(0).has("cache_control")).isFalse();
    }

    @Test
    @DisplayName("MAP 첫 패스(tool_result 없음)에는 tool_choice로 뷰포트 tool 호출을 강제한다")
    void forcesViewportToolOnFirstMapPass() throws IOException {
        // 프롬프트 문장으로는 호출이 보장되지 않았다(E2E 3/3 무시, 카드 0건). 요청 스펙으로 건다.
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}],
                 "messages":[{"role":"user","content":[{"type":"text","text":"여기 근처 카페"}]}]}
                """);

        JsonNode toolChoice = outgoing.get("tool_choice");
        assertThat(toolChoice).isNotNull();
        assertThat(toolChoice.path("type").asText()).isEqualTo("tool");
        assertThat(toolChoice.path("name").asText()).isEqualTo("searchPlacesInView");
    }

    @Test
    @DisplayName("MAP 두 번째 패스(tool_result 있음)에는 tool_choice를 걸지 않는다 — 무한 재호출 방지")
    void doesNotForceToolAfterToolResult() throws IOException {
        // 강제가 남으면 모델이 같은 tool만 끝없이 재호출한다. auto로 풀어 최종 답변을 내게 한다.
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}],
                 "messages":[
                   {"role":"user","content":[{"type":"text","text":"여기 근처 카페"}]},
                   {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"searchPlacesInView","input":{}}]},
                   {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"[]"}]}
                 ]}
                """);

        assertThat(outgoing.has("tool_choice")).isFalse();
    }

    @Test
    @DisplayName("GENERAL 요청에는 tool_choice를 걸지 않는다 — 강제는 MAP 시그니처 전용")
    void doesNotForceToolForGeneralRequest() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlaces"},{"name":"getBoard"}],
                 "messages":[{"role":"user","content":[{"type":"text","text":"부산 뭐 있어"}]}]}
                """);

        assertThat(outgoing.has("tool_choice")).isFalse();
    }

    @Test
    @DisplayName("content가 평문 문자열인 이력 메시지가 섞여 있어도 예외 없이 강제한다")
    void handlesPlainStringContentMessages() throws IOException {
        // Redis 이력은 ChatTurn(텍스트)으로 저장돼 평문 content 메시지로 복원된다.
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}],
                 "messages":[
                   {"role":"user","content":"저번에 물어본 거"},
                   {"role":"assistant","content":"네, 그때 알려드린 곳이요"},
                   {"role":"user","content":[{"type":"text","text":"여기도 알려줘"}]}
                 ]}
                """);

        assertThat(outgoing.path("tool_choice").path("name").asText()).isEqualTo("searchPlacesInView");
    }

    @Test
    @DisplayName("이미 tool_choice가 실린 요청은 덮어쓰지 않는다 — Spring AI의 의도 존중")
    void keepsExistingToolChoice() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"tools":[{"name":"searchPlacesInView","input_schema":{}}],
                 "tool_choice":{"type":"auto"},
                 "messages":[{"role":"user","content":[{"type":"text","text":"여기 근처 카페"}]}]}
                """);

        assertThat(outgoing.path("tool_choice").path("type").asText()).isEqualTo("auto");
    }

    @Test
    @DisplayName("system 평문 프리픽스를 text 블록 배열로 바꿔 cache_control을 단다 (tools+system 캐시)")
    void marksCacheControlOnSystemPrefix() throws IOException {
        JsonNode outgoing = outgoingBody("""
                {"system":"너는 이음이","tools":[{"name":"searchPlaces"}]}
                """);

        JsonNode system = outgoing.get("system");
        assertThat(system.isArray()).isTrue();
        JsonNode block = system.get(system.size() - 1);
        assertThat(block.path("type").asText()).isEqualTo("text");
        // 원문이 보존돼야 프리픽스 의미가 바뀌지 않는다.
        assertThat(block.path("text").asText()).isEqualTo("너는 이음이");
        assertThat(block.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("web_search 블록이 섞인 응답은 서버tool 블록을 지우고 text를 하나로 합친다")
    void normalizesResponseContainingWebSearchBlocks() throws IOException {
        // 발화 로깅을 얹어도 정규화 결과는 그대로여야 한다 — Spring AI가 파싱할 수 있는 형태의 회귀 방지.
        JsonNode normalized = MAPPER.readTree(normalizedResponse("""
                {"content":[
                  {"type":"text","text":"찾아봤어요. "},
                  {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"부산 야경"}},
                  {"type":"web_search_tool_result","tool_use_id":"s1","content":[]},
                  {"type":"text","text":"광안리가 좋아요."}
                ]}
                """));

        JsonNode content = normalized.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("찾아봤어요. 광안리가 좋아요.");
    }

    @Test
    @DisplayName("web_search 블록이 없는 응답은 원본 그대로 통과한다 — 클라이언트 tool_use 보존")
    void leavesResponseWithoutWebSearchUntouched() throws IOException {
        String original = """
                {"content":[{"type":"tool_use","id":"t1","name":"searchPlaces","input":{"keyword":"카페"}}]}""";

        assertThat(new String(normalizedResponse(original), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    /** interceptor를 통과시키고 정규화를 거쳐 나온 응답 body를 돌려준다. */
    private byte[] normalizedResponse(String responseBody) throws IOException {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://gms.ssafy.io/gmsapi/api.anthropic.com/v1/messages"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(eq(request), any())).thenReturn(response);

        return interceptor.intercept(request, "{}".getBytes(StandardCharsets.UTF_8), execution)
                .getBody().readAllBytes();
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

    /** 나가는 tools 배열에서 주입된 web_search 블록을 찾아 돌려준다(없으면 MissingNode). */
    private JsonNode webSearch(JsonNode outgoing) {
        for (JsonNode tool : outgoing.get("tools")) {
            if (WEB_SEARCH_TYPE.equals(tool.path("type").asText(""))) {
                return tool;
            }
        }
        return MAPPER.missingNode();
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
