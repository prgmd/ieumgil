package com.ssafy.ieumgil.domain.chatbot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Anthropic /v1/messages 요청에 서버tool web_search를 주입하고, 그 응답을 Spring AI 1.1.8이
 * 파싱할 수 있는 형태로 정규화한다.
 *
 * <p>Spring AI 1.1.8은 서버tool을 선언하는 API가 없고, web_search 응답도 두 가지 이유로 다루지 못한다:
 * <ol>
 *   <li>{@code web_search_tool_result} 블록 타입이 {@code ContentBlock.Type} enum에 없어 역직렬화 예외 발생</li>
 *   <li>web_search가 답변을 여러 {@code text} 블록으로 쪼개는데 {@code ChatClient.content()}는 첫 블록만 반환</li>
 * </ol>
 * 따라서 web_search 결과가 섞인 응답에 한해 모든 {@code text} 블록을 하나로 합치고 서버tool 블록을 제거한다.
 * web_search가 없는 일반 응답(클라이언트 {@code tool_use} 포함)은 그대로 통과시켜 기존 tool 호출 루프를 건드리지 않는다.
 */
@Slf4j
public class WebSearchInterceptor implements ClientHttpRequestInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEB_SEARCH_TYPE = "web_search_20250305";
    private static final int MAX_USES = 2;
    /** MAP 모드 시그니처 — 뷰포트 장소검색 tool의 등록명(= ViewportPlaceSearchTool.searchPlacesInView). */
    private static final String VIEWPORT_TOOL_NAME = "searchPlacesInView";

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull org.springframework.http.HttpRequest request, @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {
        if (!request.getURI().getPath().contains("/messages")) {
            return execution.execute(request, body);
        }

        byte[] outgoing = injectWebSearchTool(body);
        request.getHeaders().setContentLength(outgoing.length);
        ClientHttpResponse response = execution.execute(request, outgoing);

        byte[] respBytes = response.getBody().readAllBytes();
        byte[] normalized = normalizeResponse(respBytes);
        return new BufferedResponse(response, normalized);
    }

    /**
     * 나가는 body의 tools 배열에 web_search 서버tool을 추가하고, tools 프리픽스에 프롬프트 캐싱
     * 브레이크포인트를 단다. 실패 시 원본 그대로.
     *
     * <p>단, MAP 모드 요청(뷰포트 검색 tool 하나만 등록된 시그니처)에는 web_search를 주입하지 않는다.
     * web_search를 함께 노출하면 모델이 뷰포트 tool 대신 web_search로 산문 답을 내 추천 카드
     * (뷰포트 tool 호출의 side-effect)가 사라지기 때문이다. interceptor는 mode 신호를 받지
     * 못하므로 나가는 tools 배열을 sniff해 판정한다.
     *
     * <p>web_search 주입/스킵을 끝낸 <b>최종</b> tools 배열의 마지막 요소, 그리고 그 뒤에 렌더되는
     * system 프리픽스에 캐싱 브레이크포인트를 붙인다. 렌더 순서는 tools → system → messages다.
     * <b>haiku 4.5의 최소 캐시 프리픽스는 4096 토큰</b>인데, 실측 결과 tool 정의만으로는(~3.5-4k)
     * 이 floor를 넘지 못해 tools 단독 브레이크포인트는 캐시를 쓰지 못한다(cache_creation=0). 그래서
     * system 블록에도 브레이크포인트를 둬 <b>tools + system</b>(실측 ~5.3k 토큰)을 한 번에 캐시한다 —
     * 이 프리픽스는 대화 내(같은 프로젝트·모드) 바이트 불변이라 이후 턴이 캐시읽기(~0.1× 단가)로
     * 재사용한다. tools 마지막 마커는 floor 미달로 지금은 실효가 없지만, tool 정의가 커지는 경우를
     * 대비한 더 촘촘한 브레이크포인트로 남겨 둔다(브레이크포인트 최대 4개 중 2개).
     */
    private byte[] injectWebSearchTool(byte[] body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(body);
            JsonNode existing = root.get("tools");
            boolean mapMode = existing != null && existing.isArray() && isMapModeRequest((ArrayNode) existing);

            ArrayNode tools;
            if (mapMode) {
                tools = (ArrayNode) existing;
            } else {
                tools = existing != null && existing.isArray()
                        ? (ArrayNode) existing
                        : root.putArray("tools");
                ObjectNode ws = MAPPER.createObjectNode();
                ws.put("type", WEB_SEARCH_TYPE);
                ws.put("name", "web_search");
                ws.put("max_uses", MAX_USES);
                tools.add(ws);
            }

            markCacheBreakpoint(tools);
            markSystemCacheBreakpoint(root);
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            log.warn("web_search tool 주입/캐시 브레이크포인트 설정 실패, 원본 요청 사용", e);
            return body;
        }
    }

    /**
     * tools 배열의 마지막 요소에 캐싱 브레이크포인트({@code cache_control: ephemeral})를 단다.
     * tool이 없으면 붙일 대상이 없으므로 건너뛴다.
     */
    private void markCacheBreakpoint(ArrayNode tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        JsonNode last = tools.get(tools.size() - 1);
        if (last instanceof ObjectNode lastTool) {
            lastTool.set("cache_control", MAPPER.createObjectNode().put("type", "ephemeral"));
        }
    }

    /**
     * system 프리픽스에 캐싱 브레이크포인트를 단다. Spring AI는 {@code system}을 평문 문자열로 보내므로,
     * cache_control을 실을 수 있도록 {@code [{"type":"text","text":...,"cache_control":...}]} 배열 블록으로
     * 바꾼다. 이 브레이크포인트는 렌더 순서상 앞선 tools까지 포함한 tools+system 프리픽스를 캐시한다.
     * 이미 배열이면 마지막 블록에 붙이고, system이 없거나 빈 문자열이면 건너뛴다.
     */
    private void markSystemCacheBreakpoint(ObjectNode root) {
        JsonNode system = root.get("system");
        if (system == null) {
            return;
        }
        if (system.isTextual()) {
            String text = system.asText();
            if (text.isEmpty()) {
                return;
            }
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "text");
            block.put("text", text);
            block.set("cache_control", MAPPER.createObjectNode().put("type", "ephemeral"));
            ArrayNode arr = MAPPER.createArrayNode();
            arr.add(block);
            root.set("system", arr);
        } else if (system.isArray() && !system.isEmpty()) {
            JsonNode last = system.get(system.size() - 1);
            if (last instanceof ObjectNode lastBlock) {
                lastBlock.set("cache_control", MAPPER.createObjectNode().put("type", "ephemeral"));
            }
        }
    }

    /**
     * MAP 모드 시그니처인지 판정한다: 등록된 custom tool이 뷰포트 검색 tool 하나뿐인 요청.
     * (web_search는 아직 주입 전이라 이 시점 tools 배열에 없다.)
     */
    private boolean isMapModeRequest(ArrayNode tools) {
        return tools.size() == 1
                && VIEWPORT_TOOL_NAME.equals(tools.get(0).path("name").asText(""));
    }

    /**
     * web_search 블록이 섞인 응답에 한해 content를 정규화한다:
     * 서버tool 블록(server_tool_use, web_search_tool_result) 제거 + 모든 text 블록을 하나로 병합
     * (인용 메타 제거). web_search가 없으면 원본을 그대로 반환해 클라이언트 tool_use를 보존한다.
     */
    private byte[] normalizeResponse(byte[] respBytes) {
        try {
            JsonNode root = MAPPER.readTree(respBytes);
            if (!root.isObject() || !root.has("content") || !root.get("content").isArray()) {
                return respBytes;
            }
            ArrayNode content = (ArrayNode) root.get("content");
            boolean hasWebSearch = false;
            for (JsonNode blk : content) {
                String type = blk.path("type").asText("");
                if (type.equals("web_search_tool_result") || type.equals("server_tool_use")) {
                    hasWebSearch = true;
                    break;
                }
            }
            if (!hasWebSearch) {
                return respBytes;
            }

            ArrayNode newContent = MAPPER.createArrayNode();
            StringBuilder mergedText = new StringBuilder();
            for (JsonNode blk : content) {
                String type = blk.path("type").asText("");
                if (type.equals("text")) {
                    mergedText.append(blk.path("text").asText(""));
                } else if (!type.equals("web_search_tool_result") && !type.equals("server_tool_use")) {
                    newContent.add(blk); // 방어적으로 클라이언트 tool_use 등은 보존
                }
            }
            ObjectNode textBlock = MAPPER.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", mergedText.toString());
            newContent.add(textBlock);
            ((ObjectNode) root).set("content", newContent);
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            log.warn("web_search 응답 정규화 실패, 원본 응답 사용", e);
            return respBytes;
        }
    }

    /** 응답 body를 교체한 ClientHttpResponse 래퍼. */
    private static final class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        private BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        @NonNull
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        @NonNull
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(delegate.getHeaders());
            headers.setContentLength(body.length);
            return headers;
        }

        @Override
        @NonNull
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        @NonNull
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
