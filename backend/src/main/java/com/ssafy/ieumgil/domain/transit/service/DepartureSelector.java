package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Departure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 기준 시각 이후 편들에서 사용자에게 보여줄 3편을 고른다.
 *
 * <p>시각순으로만 3편을 고르면 등급이 편중된다 — 서울→부산 89편에는 KTX·ITX·무궁화가
 * 섞여 있는데 기준 시각 직후 3편은 KTX만 걸릴 가능성이 높다. 무궁화가 절반 값에 느린데
 * 그 선택지가 사라진다. 그래서 <b>시각순 2편 + 최저 요금 1편</b>을 고른다.
 *
 * <p>요금 비교는 일반석 기준이다. 입석으로 비교하면 같은 열차가 등급만 바꿔 최저가로 뽑힌다
 * — 호출자가 {@code fare}에 일반석 값을 담아 넘긴다.
 */
public final class DepartureSelector {

    private static final int MAX_DEPARTURES = 3;
    private static final int TIME_ORDERED_SLOTS = 2;
    private static final String CHEAPEST_LABEL = "최저 요금";

    /**
     * @param afterReference 기준 시각 이후 출발편. <b>시각 오름차순으로 정렬돼 있어야 한다</b>
     * @param fareAware      요금으로 최저가 축을 쓸 수 있는지. 항공은 요금이 없어 false다
     */
    public static List<Departure> selectThree(List<Departure> afterReference, boolean fareAware) {
        if (afterReference.isEmpty()) {
            return List.of();
        }
        if (!fareAware || afterReference.size() <= TIME_ORDERED_SLOTS) {
            return afterReference.stream().limit(MAX_DEPARTURES).toList();
        }

        List<Departure> selected = new ArrayList<>(afterReference.subList(
                0, Math.min(TIME_ORDERED_SLOTS, afterReference.size())));

        List<Departure> rest = afterReference.subList(selected.size(), afterReference.size());
        Optional<Departure> cheapest = rest.stream()
                .filter(d -> d.fare() != null)
                .min(Comparator.comparingInt(Departure::fare));

        if (cheapest.isPresent()) {
            selected.add(withLabel(cheapest.get(), CHEAPEST_LABEL));
        } else {
            selected.add(rest.get(0));
        }
        return List.copyOf(selected);
    }

    private static Departure withLabel(Departure departure, String label) {
        List<String> labels = new ArrayList<>(departure.labels() == null ? List.of() : departure.labels());
        if (!labels.contains(label)) {
            labels.add(label);
        }
        return Departure.builder()
                .name(departure.name())
                .grade(departure.grade())
                .departureAt(departure.departureAt())
                .arrivalAt(departure.arrivalAt())
                .durationMin(departure.durationMin())
                .fare(departure.fare())
                .fareConfidence(departure.fareConfidence())
                .fareOptions(departure.fareOptions())
                .labels(List.copyOf(labels))
                .build();
    }

    private DepartureSelector() {
    }
}
