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
import java.util.ArrayList;
import java.util.List;

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
    /**
     * MAP 모드의 web_search 예산. 지도 추천은 뷰포트 tool 결과가 본체이고 웹 검색은 그 결과를
     * 고르고 설명하는 보조 신호라, 검색은 "지역+종류" 한 번이면 충분하다. 왕복을 줄여 지연을
     * 억제하고, 모델이 검색을 이어가다 tool 결과에서 멀어지는 여지도 줄인다.
     */
    private static final int MAP_MAX_USES = 1;
    /** MAP 모드 시그니처 — 뷰포트 장소검색 tool의 등록명(= ViewportPlaceSearchTool.searchPlacesInView). */
    private static final String VIEWPORT_TOOL_NAME = "searchPlacesInView";
    private static final String TOOL_CHOICE = "tool_choice";
    /**
     * web_search 발화 로그의 고정 접두사. E2E 하네스({@code ChatbotDemoScenarioLiveTest})가
     * 이 접두사로 로그를 골라내므로 <b>하네스가 이 상수를 직접 참조하도록 public으로 둔다</b> —
     * 문자열을 양쪽에 복제하면 한쪽만 바뀌었을 때 관측이 조용히 죽고, 그러면 "web_search가
     * 아예 안 불린다"는 잘못된 결론으로 프롬프트를 고치게 된다.
     */
    public static final String WEB_SEARCH_LOG_PREFIX = "web_search 사용";

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
     * <p>MAP 모드 요청(뷰포트 검색 tool 하나만 등록된 시그니처)에도 web_search를 주입하되
     * 예산만 {@link #MAP_MAX_USES}로 낮춘다. 예전에는 MAP에서 아예 스킵했는데, 그 근거였던
     * "web_search를 노출하면 모델이 뷰포트 tool 대신 산문으로 답해 카드가 사라진다"는 인과는
     * E2E 실행에서 반증됐다 — 스킵된 상태에서도 모델이 직전 턴 이력만 보고 tool을 건너뛴 사례가
     * 나왔다. tool을 부르게 만드는 것은 주입 여부가 아니라 프롬프트({@code ChatbotPrompt.MAP_TAIL})이고,
     * 카드는 뷰포트 tool 결과에서만 만들어지므로 웹 검색이 카드를 오염시키지도 않는다.
     * interceptor는 mode 신호를 받지 못하므로 나가는 tools 배열을 sniff해 예산을 정한다.
     *
     * <p>web_search 주입/스킵을 끝낸 <b>최종</b> tools 배열의 마지막 요소, 그리고 그 뒤에 렌더되는
     * system 프리픽스에 캐싱 브레이크포인트를 붙인다. 렌더 순서는 tools → system → messages다.
     * <b>haiku 4.5의 최소 캐시 프리픽스는 4096 토큰</b>인데, 실측 결과 tool 정의만으로는(~3.5-4k)
     * 이 floor를 넘지 못해 tools 단독 브레이크포인트는 캐시를 쓰지 못한다(cache_creation=0). 그래서
     * system 블록에도 브레이크포인트를 둬 <b>tools + system</b>(실측 ~5.3k 토큰)을 한 번에 캐시한다 —
     * 이 프리픽스는 대화 내(같은 프로젝트·모드) 바이트 불변이라 이후 턴이 캐시읽기(~0.1× 단가)로
     * 재사용한다. tools 마지막 마커는 floor 미달로 지금은 실효가 없지만, tool 정의가 커지는 경우를
     * 대비한 더 촘촘한 브레이크포인트로 남겨 둔다(브레이크포인트 최대 4개 중 2개).
     *
     * <p><b>MAP 모드 첫 패스에는 {@code tool_choice} 로 뷰포트 tool 호출을 강제한다.</b>
     * 이 보장을 프롬프트가 아니라 요청 스펙에 두는 이유는 프롬프트가 실패했기 때문이다 —
     * E2E에서 {@code ChatbotPrompt.MAP_TAIL} 의 "되묻지 마세요"·"매 턴 반드시 호출" 문장이
     * MAP 시나리오 3/3에서 무시돼 tool 호출 0건·카드 0건이 났고, 3건 모두 "어느 지역을 보고
     * 계신가요?"로 되물었다. 문장은 확률을 올릴 뿐 호출을 보장하지 못한다. 카드(추천 후보)는
     * 오직 뷰포트 tool 호출의 부수효과로만 만들어지므로, 호출 0건은 곧 기능 0건이다.
     *
     * <p>강제는 <b>첫 패스에만</b> 건다. tool 결과가 돌아온 뒤에도 강제가 남아 있으면 모델이
     * 같은 tool을 끝없이 재호출한다(그 tool 말고는 낼 수 있는 응답이 없으므로). 요청에
     * tool_result 블록이 보이면 이번 턴의 tool 루프가 이미 한 바퀴 돈 것이니 강제를 풀어
     * auto로 두고, 모델이 이어서 web_search를 쓰거나 최종 답변을 내게 한다. Spring AI가
     * 이미 {@code tool_choice} 를 넣어 보냈다면 그 의도를 존중해 덮어쓰지 않는다.
     *
     * <p>최악의 경우 비용은 헛검색 한 번이다 — 사용자가 "고마워" 같은 잡담을 해도 뷰포트
     * 검색이 한 번 돈다. 지도 추천 모드에서 카드가 0건으로 나오는 것보다 싸다고 봤다.
     */
    private byte[] injectWebSearchTool(byte[] body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(body);
            JsonNode existing = root.get("tools");
            ArrayNode tools = existing != null && existing.isArray()
                    ? (ArrayNode) existing
                    : root.putArray("tools");

            // web_search 추가 전 배열로 판정해야 시그니처(custom tool 하나뿐)가 성립한다.
            boolean mapMode = isMapModeRequest(tools);

            ObjectNode ws = MAPPER.createObjectNode();
            ws.put("type", WEB_SEARCH_TYPE);
            ws.put("name", "web_search");
            ws.put("max_uses", mapMode ? MAP_MAX_USES : MAX_USES);
            tools.add(ws);

            if (mapMode && !root.has(TOOL_CHOICE) && !hasToolResult(root)) {
                ObjectNode toolChoice = MAPPER.createObjectNode();
                toolChoice.put("type", "tool");
                toolChoice.put("name", VIEWPORT_TOOL_NAME);
                root.set(TOOL_CHOICE, toolChoice);
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
     * (web_search는 아직 주입 전이라 이 시점 tools 배열에 없다.) 판정 결과는 web_search 예산
     * ({@link #MAP_MAX_USES} vs {@link #MAX_USES})을 정하는 데 쓴다.
     */
    private boolean isMapModeRequest(ArrayNode tools) {
        return tools.size() == 1
                && VIEWPORT_TOOL_NAME.equals(tools.get(0).path("name").asText(""));
    }

    /**
     * 요청 messages 안에 {@code tool_result} 블록이 하나라도 있는지 — 즉 이번 턴의 tool 루프가
     * 이미 한 바퀴 돌았는지 판정한다. content가 평문 문자열인 메시지(대화 이력 복원분)는 건너뛴다.
     *
     * <p>이 판정이 "이번 턴"을 뜻하는 근거: Redis 대화 이력은 {@code ChatTurn}(사용자 텍스트 +
     * 답변 텍스트)로만 저장돼 평문 메시지로 복원되므로 지난 턴의 tool_result는 요청에 실리지
     * 않는다. 이력 저장 방식이 블록 단위로 바뀌면 이 가정이 깨진다.
     */
    private boolean hasToolResult(JsonNode root) {
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return false;
        }
        for (JsonNode message : messages) {
            JsonNode content = message.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode block : content) {
                if ("tool_result".equals(block.path("type").asText(""))) {
                    return true;
                }
            }
        }
        return false;
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
            logWebSearchUse(content);

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

    /**
     * 모델이 web_search를 실제로 썼는지를 남긴다 — 응답 말고는 알 길이 없기 때문이다.
     * {@code ChatbotPrompt.MAP_TAIL}은 "웹에서 알게 된 것은 웹에서 확인했다고 밝히라"고 요구하는데
     * 라이브 20시나리오에서 그런 문장이 0건이었다. 추측으로 프롬프트를 고치지 않으려면 발화 여부를
     * 먼저 봐야 한다. web_search 블록이 <b>있을 때만</b> 남긴다(없으면 매 응답 로그가 되어 소음).
     */
    private void logWebSearchUse(ArrayNode content) {
        int resultBlocks = 0;
        List<String> queries = new ArrayList<>();
        for (JsonNode blk : content) {
            String type = blk.path("type").asText("");
            if (type.equals("web_search_tool_result")) {
                resultBlocks++;
            } else if (type.equals("server_tool_use")) {
                String query = blk.path("input").path("query").asText("");
                if (!query.isEmpty()) {
                    queries.add(query);
                }
            }
        }
        if (queries.isEmpty()) {
            log.info("{} — 결과 블록 {}건", WEB_SEARCH_LOG_PREFIX, resultBlocks);
        } else {
            log.info("{} — 결과 블록 {}건, 검색어 {}", WEB_SEARCH_LOG_PREFIX, resultBlocks, queries);
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
