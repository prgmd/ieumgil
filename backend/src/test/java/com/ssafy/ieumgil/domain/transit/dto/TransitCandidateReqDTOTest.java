package com.ssafy.ieumgil.domain.transit.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TransitCandidateReqDTOTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("블록 30개까지는 허용한다")
    void acceptsUpToThirtyBlocks() {
        List<Long> ids = IntStream.rangeClosed(1, 30).mapToObj(Long::valueOf).toList();

        assertThat(validator.validate(new TransitCandidateReqDTO.Calculate(ids))).isEmpty();
    }

    @Test
    @DisplayName("블록 31개는 거절한다 — 한 요청으로 외부 API 쿼터를 태우지 못하게")
    void rejectsMoreThanThirtyBlocks() {
        List<Long> ids = IntStream.rangeClosed(1, 31).mapToObj(Long::valueOf).toList();

        assertThat(validator.validate(new TransitCandidateReqDTO.Calculate(ids))).isNotEmpty();
    }

    @Test
    @DisplayName("blockIds가 null이면 거절한다")
    void rejectsNullBlockIds() {
        assertThat(validator.validate(new TransitCandidateReqDTO.Calculate(null))).isNotEmpty();
    }

    @Test
    @DisplayName("블록이 하나뿐이어도 요청 자체는 유효하다 — 구간이 안 생길 뿐이다")
    void acceptsSingleBlock() {
        assertThat(validator.validate(new TransitCandidateReqDTO.Calculate(List.of(1L)))).isEmpty();
    }
}
