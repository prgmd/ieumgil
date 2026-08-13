package com.ssafy.ieumgil.domain.festival;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FestivalCategoryTest {

    @Test
    @DisplayName("EV01~EV03은 각각 축제·공연·행사로 접는다")
    void 알려진_코드를_라벨로_접는다() {
        assertThat(FestivalCategory.labelOf("EV01")).isEqualTo("축제");
        assertThat(FestivalCategory.labelOf("EV02")).isEqualTo("공연");
        assertThat(FestivalCategory.labelOf("EV03")).isEqualTo("행사");
    }

    @Test
    @DisplayName("미지·누락 코드는 '행사'로 접는다 — 두 매핑의 default와 같다")
    void 미지_코드는_행사로_접는다() {
        assertThat(FestivalCategory.labelOf("EV99")).isEqualTo("행사");
        assertThat(FestivalCategory.labelOf("")).isEqualTo("행사");
        assertThat(FestivalCategory.labelOf(null)).isEqualTo("행사");
    }
}
