package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OdsayRouteResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OdsayRouteResponse read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/odsay/" + name)) {
            return mapper.readValue(in, OdsayRouteResponse.class);
        }
    }

    @Test
    @DisplayName("시외 경로는 payment 대신 totalPayment로 요금을 준다")
    void 시외_요금은_totalPayment다() throws IOException {
        OdsayRouteResponse r = read("odsay-intercity.json");

        OdsayRouteResponse.Path train = r.result().path().stream()
                .filter(p -> p.pathType() == 11).findFirst().orElseThrow();

        assertThat(train.info().payment()).as("시외에는 payment가 없다").isNull();
        assertThat(train.info().totalPayment()).isEqualTo(59800);
        assertThat(train.info().transitCount()).isNotNull();
    }

    @Test
    @DisplayName("시외 leg는 역 ID·좌표·구간요금을 준다 — 이름 검색이 필요 없다")
    void 시외_leg가_ID와_좌표를_준다() throws IOException {
        OdsayRouteResponse r = read("odsay-intercity.json");
        OdsayRouteResponse.SubPath leg = r.result().path().stream()
                .filter(p -> p.pathType() == 11).findFirst().orElseThrow()
                .subPath().get(0);

        assertThat(leg.startID()).isEqualTo(3300128);   // 서울역
        assertThat(leg.endID()).isEqualTo(3300108);     // 부산역
        assertThat(leg.startX()).isNotNull();
        assertThat(leg.startY()).isNotNull();
        assertThat(leg.payment()).isEqualTo(59800);
        assertThat(leg.trainType()).isNotBlank();
    }

    @Test
    @DisplayName("항공 leg의 trafficType은 7이다 — 6이 아니다")
    void 항공은_trafficType_7이다() throws IOException {
        OdsayRouteResponse r = read("odsay-intercity.json");
        OdsayRouteResponse.SubPath air = r.result().path().stream()
                .filter(p -> p.pathType() == 13).findFirst().orElseThrow()
                .subPath().get(0);

        assertThat(air.trafficType()).isEqualTo(7);
        assertThat(air.startID()).isBetween(3500000, 3599999);
    }

    @Test
    @DisplayName("시외 경로에는 접근·이탈 leg가 없다 — 역 아닌 좌표로도")
    void 시외_경로에_접근_leg가_없다() throws IOException {
        OdsayRouteResponse r = read("odsay-nonstation.json");

        r.result().path().stream().filter(p -> p.pathType() >= 11).forEach(p ->
                assertThat(p.subPath())
                        .as("시외 경로 %d에 시내 leg가 섞였다", p.pathType())
                        .noneMatch(s -> s.trafficType() <= 3));
    }

    @Test
    @DisplayName("시내 경로는 payment와 접근 leg를 준다")
    void 시내_경로는_payment와_접근_leg를_준다() throws IOException {
        OdsayRouteResponse r = read("odsay-access.json");
        OdsayRouteResponse.Path p = r.result().path().get(0);

        assertThat(p.info().payment()).isEqualTo(1550);
        assertThat(p.subPath()).anyMatch(s -> s.trafficType() == 3);   // 도보
        assertThat(p.subPath()).anyMatch(s -> s.trafficType() == 1);   // 지하철
    }

    @Test
    @DisplayName("객체형 에러(경로 없음)를 파싱하고 code를 준다")
    void 객체형_에러를_파싱한다() throws IOException {
        OdsayRouteResponse r = read("error-no-result.json");

        assertThat(r.errorCode()).isEqualTo("-99");
        assertThat(r.result()).isNull();
    }

    @Test
    @DisplayName("배열형 에러(인증 실패)도 파싱한다")
    void 배열형_에러를_파싱한다() throws IOException {
        OdsayRouteResponse r = read("error-auth.json");

        assertThat(r.errorCode()).isEqualTo("500");
    }

    @Test
    @DisplayName("에러 형태가 드리프트하면(원소가 Map이 아니면) 예외 대신 null로 접는다")
    void 드리프트한_에러_형태는_null이다() throws IOException {
        // 배열의 첫 원소가 객체가 아니라 문자열/숫자로 오는 등 스키마가 바뀌어도 ClassCastException으로
        // 터지지 않고 null을 줘야 한다 — 안 그러면 응답 드리프트가 500으로 새어 나간다.
        assertThat(mapper.readValue("{\"error\":[\"oops\"]}", OdsayRouteResponse.class).errorCode()).isNull();
        assertThat(mapper.readValue("{\"error\":[123]}", OdsayRouteResponse.class).errorCode()).isNull();
        assertThat(mapper.readValue("{\"error\":\"boom\"}", OdsayRouteResponse.class).errorCode()).isNull();
        assertThat(mapper.readValue("{\"error\":[]}", OdsayRouteResponse.class).errorCode()).isNull();
    }
}
