package com.ssafy.ieumgil.domain.festival.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HomepageUrlExtractorTest {

    @Test
    @DisplayName("HTML 앵커에서 href URL을 뽑는다")
    void 앵커에서_href를_뽑는다() {
        String raw = "<a href=\"http://www.boryeongmudfestival.com\" target=\"_blank\">머드축제</a>";
        assertThat(HomepageUrlExtractor.extract(raw)).isEqualTo("http://www.boryeongmudfestival.com");
    }

    @Test
    @DisplayName("작은따옴표 href도 뽑는다")
    void 작은따옴표_href() {
        assertThat(HomepageUrlExtractor.extract("<a href='https://example.or.kr'>사이트</a>"))
                .isEqualTo("https://example.or.kr");
    }

    @Test
    @DisplayName("평문 URL은 그대로 쓴다")
    void 평문_URL() {
        assertThat(HomepageUrlExtractor.extract("https://plain.example.com")).isEqualTo("https://plain.example.com");
    }

    @Test
    @DisplayName("빈 문자열·null·URL 없는 텍스트는 null이다")
    void 빈값은_null() {
        assertThat(HomepageUrlExtractor.extract("")).isNull();
        assertThat(HomepageUrlExtractor.extract(null)).isNull();
        assertThat(HomepageUrlExtractor.extract("<a>링크 없음</a>")).isNull();
    }
}
