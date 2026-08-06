package com.ssafy.ieumgil.domain.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LIVE 시나리오 테스트 — 컨트롤러부터 실제 ODsay·카카오 API까지 전 구간을 태운다.
 *
 * <p>목 없이 돈다. {@code TransitCandidateAuthorizationTest}가 외부 호출을 끊고 인가 계층만
 * 보는 것과 정반대 목적이다: 실제 응답으로 조립한 후보가 사용자에게 나갈 만한 모양인지 본다.
 * 단위 테스트가 잡지 못하는 것을 잡는다 — 픽스처가 아닌 실제 ODsay 경로 조합, 실제 카카오
 * 길찾기 응답, 실제 시간표.
 *
 * <p>키가 환경변수에 없으면 SKIP한다({@code ODSAY_API_KEY}·{@code KAKAO_REST_API_KEY}).
 * ODsay는 허용 IP만 받으므로 등록되지 않은 곳에서는 통째로 실패한다 — 그래서 {@code @Tag("live")}로
 * 기본 실행에서 빠지고 CI 게이트가 되지 않는다. 실행:
 * {@code set -a; . ./.env; set +a; sh gradlew test -PincludeLive --tests "*TransitCandidateLiveScenarioTest*"}
 *
 * <p>단정은 <b>구조 불변식</b>만 건다. 시간표·요금은 날마다 바뀌므로 값을 못 박으면 내일
 * 깨진다. 대신 "제주 구간에 택시 후보가 없다", "육로 구간에는 있다"처럼 이번 수정이 만든
 * 계약을 확인하고, 나머지는 사람이 읽도록 표로 찍는다.
 */
@Tag("live")
@AutoConfigureMockMvc
@DisplayName("[live] 교통 후보 전 구간 시나리오")
class TransitCandidateLiveScenarioTest extends IntegrationTestSupport {

    // --- 실좌표 ---
    private static final Coord 서울시청 = new Coord("서울시청", 37.5663, 126.9779);
    private static final Coord 강남역 = new Coord("강남역", 37.4979, 127.0276);
    private static final Coord 시청_바로옆 = new Coord("덕수궁", 37.5658, 126.9751);
    private static final Coord 부산시청 = new Coord("부산시청", 35.1796, 129.0756);
    private static final Coord 제주시청 = new Coord("제주시청", 33.4996, 126.5312);
    private static final Coord 여수시청 = new Coord("여수시청", 34.7604, 127.6622);
    private static final Coord 안동시청 = new Coord("안동시청", 36.5684, 128.7294);
    private static final Coord 목포시청 = new Coord("목포시청", 34.8118, 126.3922);
    private static final Coord 울릉도 = new Coord("울릉군청", 37.4845, 130.9058);
    private static final Coord 속초시청 = new Coord("속초시청", 38.2070, 128.5918);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    BlockRepository blockRepository;
    @Autowired
    ObjectMapper objectMapper;

    private User member;

    @BeforeEach
    void requireLiveKeys() {
        Assumptions.assumeTrue(hasKey("ODSAY_API_KEY"), "ODSAY_API_KEY 없음 — live 테스트 SKIP");
        Assumptions.assumeTrue(hasKey("KAKAO_REST_API_KEY"), "KAKAO_REST_API_KEY 없음 — live 테스트 SKIP");
        member = seedUser();
    }

    private boolean hasKey(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    @Test
    @DisplayName("시내·시외·도서·근거리 구간을 한 번에 훑는다 — 수단별 후보가 실제로 어떻게 나오는지")
    void 전_구간_시나리오() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario("근거리(300m 미만) — 도보만", 시청_바로옆, 서울시청, TransportPref.PUBLIC),
                new Scenario("시내 대중교통 — 서울시청→강남역", 서울시청, 강남역, TransportPref.PUBLIC),
                new Scenario("시내 자차 선호 — 서울시청→강남역", 서울시청, 강남역, TransportPref.CAR),
                new Scenario("시외 육로(기차·버스 모두) — 서울→부산", 서울시청, 부산시청, TransportPref.PUBLIC),
                new Scenario("시외 육로 자차 선호 — 서울→부산", 서울시청, 부산시청, TransportPref.CAR),
                new Scenario("시외 육로 — 서울→여수", 서울시청, 여수시청, TransportPref.PUBLIC),
                new Scenario("시외 육로 지방↔지방 — 안동→목포", 안동시청, 목포시청, TransportPref.PUBLIC),
                new Scenario("시외 육로 철도 없음 — 서울→속초", 서울시청, 속초시청, TransportPref.PUBLIC),
                new Scenario("도서(항공만) — 서울→제주", 서울시청, 제주시청, TransportPref.PUBLIC),
                new Scenario("도서(항공만) 자차 선호 — 서울→제주", 서울시청, 제주시청, TransportPref.CAR),
                new Scenario("경로 없음(여객선만) — 서울→울릉도", 서울시청, 울릉도, TransportPref.PUBLIC));

        List<Outcome> outcomes = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            outcomes.add(run(scenario));
        }

        print(outcomes);
        assertContracts(outcomes);
    }

    /** 한 시나리오 = 프로젝트 하나 + 블록 둘 + 엔드포인트 한 번. 시나리오마다 새 프로젝트다 */
    private Outcome run(Scenario scenario) throws Exception {
        Project project = seedProject(member);
        // 시외 시간표는 여행 날짜의 운행 요일로 걸러진다 — 오늘로 두면 막차 지난 시각에 돌릴 때
        // NO_SERVICE만 나와 아무것도 보이지 않는다. 넉넉히 뒤 평일로 잡는다.
        project.updateInfo(null, LocalDate.now().plusDays(7), LocalDate.now().plusDays(9), null);
        project.changeTransportPref(List.of(scenario.pref()));
        projectRepository.save(project);

        // from 블록의 종료 시각(09:00 + 60분 = 10:00)이 이 구간의 기준(base)이다
        Block from = seedBlock(project, scenario.from(), LocalTime.of(9, 0));
        Block to = seedBlock(project, scenario.to(), LocalTime.of(18, 0));

        String body = """
                {"blockIds": [%d, %d]}""".formatted(from.getId(), to.getId());
        String json = mockMvc.perform(post("/api/projects/{projectId}/transit-candidates", project.getId())
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(member.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(json);
        return new Outcome(scenario, root);
    }

    private Block seedBlock(Project project, Coord coord, LocalTime startTime) {
        return blockRepository.save(Block.builder()
                .name(coord.name())
                .category(BlockCategory.SPOT)
                .orderKey("a" + blockRepository.count())
                .source(BlockSource.MANUAL)
                .project(project)
                .author(member)
                .dayNo(1)
                .startTime(startTime)
                .durationMin(60)
                .lat(BigDecimal.valueOf(coord.lat()))
                .lng(BigDecimal.valueOf(coord.lng()))
                .build());
    }

    // ----- 출력: 사람이 읽고 판단하도록 실제 값을 그대로 찍는다 -----

    private void print(List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("\n\n===== 교통 후보 라이브 결과 =====\n");
        for (Outcome outcome : outcomes) {
            sb.append("\n──────────────────────────────────────────────\n")
                    .append("▶ ").append(outcome.scenario().label())
                    .append("  [선호 ").append(outcome.scenario().pref()).append("]\n");

            JsonNode segment = outcome.segment();
            if (segment == null) {
                sb.append("  !! 응답에 segment 없음: ").append(outcome.root().toString()).append('\n');
                continue;
            }
            sb.append("  intercity=").append(segment.path("intercity").asBoolean())
                    .append("  timetableApplied=").append(segment.path("timetableApplied").asBoolean())
                    .append("  defaultMode=").append(text(segment, "defaultMode"));
            if (!segment.path("timetableSkipReason").isNull()) {
                sb.append("\n  skipReason=").append(text(segment, "timetableSkipReason"));
            }
            sb.append('\n');

            for (JsonNode candidate : segment.path("candidates")) {
                sb.append("    · ").append(pad(text(candidate, "label"), 16))
                        .append(pad(text(candidate, "status"), 15))
                        .append(pad(num(candidate, "durationMin") + "분", 9))
                        .append(pad(num(candidate, "fare") + "원", 12));
                if (!candidate.path("accessMin").isNull()) {
                    sb.append(" 접근 ").append(num(candidate, "accessMin")).append("분")
                            .append(" 이탈 ").append(num(candidate, "egressMin")).append("분")
                            .append(" 기준 ").append(text(candidate, "referenceAt"));
                }
                if (!candidate.path("transferCount").isNull()) {
                    sb.append(" 환승 ").append(num(candidate, "transferCount")).append("회");
                }
                sb.append('\n');

                for (JsonNode departure : candidate.path("departures")) {
                    sb.append("        - ").append(pad(text(departure, "name"), 18))
                            .append(text(departure, "departureAt")).append("→").append(text(departure, "arrivalAt"))
                            .append("  ").append(num(departure, "durationMin")).append("분")
                            .append("  ").append(num(departure, "fare")).append("원")
                            .append("  대기 ").append(num(departure, "waitMin")).append("분");
                    JsonNode connection = departure.path("connection");
                    if (!connection.isMissingNode() && !connection.isNull()) {
                        sb.append("\n            ↳ 환승 ").append(text(connection, "fromStation"))
                                .append(" → ").append(text(connection, "toStation"))
                                .append("  ").append(text(connection, "name"))
                                .append("  ").append(text(connection, "departureAt"))
                                .append("→").append(text(connection, "arrivalAt"))
                                .append("  환승대기 ").append(num(connection, "transferMin")).append("분");
                    }
                    sb.append('\n');
                }
            }
        }
        System.out.println(sb.append("\n===== 끝 =====\n"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "-" : value.asText();
    }

    private String num(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "-" : value.asText();
    }

    private String pad(String value, int width) {
        // 한글은 2칸으로 세야 표가 맞는다
        int visual = value.codePoints().map(c -> c > 0x1100 ? 2 : 1).sum();
        return value + " ".repeat(Math.max(1, width - visual));
    }

    // ----- 단정: 이번 수정이 만든 계약만. 시간표·요금 값은 날마다 바뀌므로 못 박지 않는다 -----

    private void assertContracts(List<Outcome> outcomes) {
        for (Outcome outcome : outcomes) {
            assertThat(outcome.segment())
                    .as("%s: 응답에 segment가 있어야 한다 — %s",
                            outcome.scenario().label(), outcome.root())
                    .isNotNull();
        }

        // 도서 목적지: 자차·택시가 아예 없다. 카카오는 서울→제주에 "길찾기 성공"으로
        // 527,600원을 답하므로, 판정이 빠지면 이 단정이 곧바로 깨진다.
        for (Outcome jeju : byLabelContains(outcomes, "서울→제주")) {
            assertThat(jeju.modes())
                    .as("%s: 차로 갈 수 없으므로 자차·택시 후보가 없어야 한다", jeju.scenario().label())
                    .doesNotContain("CAR", "TAXI");
            // 기차·고속버스도 없다 — ODsay가 그 수단만으로 가는 경로를 주지 않는다
            assertThat(jeju.modes())
                    .as("%s: ODsay가 경로를 주지 않은 수단은 후보에 없어야 한다", jeju.scenario().label())
                    .doesNotContain("TRAIN", "EXPRESS_BUS");
            assertThat(jeju.modes())
                    .as("%s: 항공은 남아야 한다", jeju.scenario().label())
                    .contains("AIR");
        }

        // 육로 시외: 택시는 남는다(자차는 선호가 CAR일 때만)
        for (Outcome land : byLabelContains(outcomes, "서울→부산")) {
            assertThat(land.modes())
                    .as("%s: 육로로 갈 수 있으므로 택시 후보가 남아야 한다", land.scenario().label())
                    .contains("TAXI");
        }
        assertThat(single(outcomes, "시외 육로 자차 선호 — 서울→부산").modes())
                .as("CAR 선호면 자차 후보가 있어야 한다")
                .contains("CAR");
        assertThat(single(outcomes, "시외 육로(기차·버스 모두) — 서울→부산").modes())
                .as("PUBLIC 선호면 자차 후보는 없다 — '차로 다니겠다'를 매 구간 되묻지 않는 규칙")
                .doesNotContain("CAR");

        // 근거리는 도보만이다 — 외부 호출 자체를 생략하는 경로
        assertThat(single(outcomes, "근거리(300m 미만) — 도보만").modes())
                .as("300m 미만은 도보만 낸다")
                .containsExactly("WALK");

        // 복합 경로 라벨: 수단이 섞이면 두 수단이 다 보여야 한다
        for (Outcome outcome : outcomes) {
            for (JsonNode candidate : outcome.segment().path("candidates")) {
                String label = text(candidate, "label");
                if (label.contains("+")) {
                    assertThat(label.split("\\+"))
                            .as("%s: 조합 라벨은 서로 다른 수단이어야 한다 — '기차+기차'는 잡음이다",
                                    outcome.scenario().label())
                            .doesNotHaveDuplicates();
                }
            }
        }
    }

    private List<Outcome> byLabelContains(List<Outcome> outcomes, String fragment) {
        return outcomes.stream().filter(o -> o.scenario().label().contains(fragment)).toList();
    }

    private Outcome single(List<Outcome> outcomes, String label) {
        return outcomes.stream()
                .filter(o -> o.scenario().label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("시나리오 없음: " + label));
    }

    // ----- 값 객체 -----

    private record Coord(String name, double lat, double lng) {
    }

    private record Scenario(String label, Coord from, Coord to, TransportPref pref) {
    }

    private record Outcome(Scenario scenario, JsonNode root) {

        JsonNode segment() {
            JsonNode segments = root.path("result").path("segments");
            return segments.isArray() && !segments.isEmpty() ? segments.get(0) : null;
        }

        /** 이 구간에 실제로 나온 수단 목록. 후보 부재 계약을 여기서 확인한다 */
        Set<String> modes() {
            Set<String> modes = new LinkedHashSet<>();
            for (JsonNode candidate : segment().path("candidates")) {
                modes.add(candidate.path("mode").asText());
            }
            return modes;
        }
    }
}
