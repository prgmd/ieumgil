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

    /** 나가는 body의 tools 배열에 web_search 서버tool을 추가한다. 실패 시 원본 그대로. */
    private byte[] injectWebSearchTool(byte[] body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(body);
            ArrayNode tools = root.has("tools") && root.get("tools").isArray()
                    ? (ArrayNode) root.get("tools")
                    : root.putArray("tools");
            ObjectNode ws = MAPPER.createObjectNode();
            ws.put("type", WEB_SEARCH_TYPE);
            ws.put("name", "web_search");
            ws.put("max_uses", MAX_USES);
            tools.add(ws);
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            log.warn("web_search tool 주입 실패, 원본 요청 사용", e);
            return body;
        }
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
