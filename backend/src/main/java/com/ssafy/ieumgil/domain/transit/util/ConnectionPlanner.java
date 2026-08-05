package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Connection;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Departure;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 환승 시외 구간(leg 2개)에서 첫 편마다 실제로 탈 수 있는 연결편을 고른다.
 *
 * <p>첫 편 도착 + 두 번째 leg 탑승 여유({@link BoardingMargin#minutesFor})를 지난 시각부터
 * 가장 빠른 연결편을 붙인다. 연결편을 찾지 못한 첫 편은 결과에서 뺀다 — 탈 수 없는 조합을
 * 후보로 보여주지 않는다.
 *
 * <p>자정을 넘기는 환승은 다루지 않는다. 도착+여유가 자정을 넘기면(하루 1440분 이상) 그
 * 편은 연결편을 찾지 않는다 — 두 번째 leg 시간표를 다음 날까지 조회하지 않으므로, 넘긴
 * 걸로 추측해 이튿날 첫차를 잘못 붙이는 대신 "연결편 없음"으로 낸다.
 */
public final class ConnectionPlanner {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private ConnectionPlanner() {
    }

    /**
     * @param secondLeg 두 번째 leg 시간표 전체. 호출자가 한 번만 조회해 넘긴다 — 이 메서드는
     *                  첫 편마다 이 목록을 훑을 뿐 다시 조회하지 않는다.
     * @return 연결편을 찾은 첫 편만 담는다({@code connection}이 채워진 편만).
     */
    public static List<Departure> connect(
            List<Departure> firstLeg, List<Departure> secondLeg, TransitMode secondMode) {
        int marginMin = BoardingMargin.minutesFor(secondMode);
        List<Departure> result = new ArrayList<>();
        for (Departure first : firstLeg) {
            connectionFor(first, secondLeg, marginMin).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private static Optional<Departure> connectionFor(Departure first, List<Departure> secondLeg, int marginMin) {
        if (first.arrivalAt() == null) {
            return Optional.empty();
        }
        int arrivalMinutes = minutesOf(first.arrivalAt());
        int requiredMinutes = arrivalMinutes + marginMin;
        if (requiredMinutes >= MINUTES_PER_DAY) {
            return Optional.empty();
        }
        return secondLeg.stream()
                .filter(second -> minutesOf(second.departureAt()) >= requiredMinutes)
                .min(Comparator.comparingInt(second -> minutesOf(second.departureAt())))
                .map(second -> withConnection(first, second, minutesOf(second.departureAt()) - arrivalMinutes));
    }

    private static Departure withConnection(Departure first, Departure second, int transferMin) {
        Connection connection = Connection.builder()
                .name(second.name())
                .grade(second.grade())
                .departureAt(second.departureAt())
                .arrivalAt(second.arrivalAt())
                .durationMin(second.durationMin())
                .fare(second.fare())
                .transferMin(transferMin)
                .build();
        return first.toBuilder().connection(connection).build();
    }

    private static int minutesOf(String hhmm) {
        LocalTime time = LocalTime.parse(hhmm);
        return time.getHour() * 60 + time.getMinute();
    }
}
