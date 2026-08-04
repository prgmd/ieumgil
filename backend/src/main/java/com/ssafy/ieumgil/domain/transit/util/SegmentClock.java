package com.ssafy.ieumgil.domain.transit.util;

import java.time.LocalTime;

/**
 * 구간별 출발 기준 시각을 누적한다.
 *
 * <p>시외 후보는 실제 편을 골라야 하므로 "이 구간을 몇 시 이후에 출발할 수 있는가"가 필요하다.
 * 일괄 계산은 교통 블록이 없을 때 부르므로 장소 블록이 이동시간 0으로 붙어 있고, DB에 저장된
 * 시각은 이동을 반영하지 않은 값이다. 그래서 서버가 직접 누적한다.
 *
 * <p><b>자정을 넘겨도 자르지 않는다.</b> 밤 기차로 이동해 아침에 도착하는 일정이 유효하다 —
 * 자정에서 끊으면 심야 이동이 후보에서 사라진다. Day 경계 판정은 프론트 몫이다.
 *
 * <p>가변 상태를 갖지만 요청 하나의 구간 순회 안에서만 쓰이므로 공유되지 않는다.
 */
public class SegmentClock {

    /** 역·터미널·공항 이동 버퍼(분). 명세 BLK-04 */
    public static final int STATION_BUFFER_MINUTES = 45;

    private LocalTime cursor;

    public SegmentClock(LocalTime dayStart) {
        this.cursor = dayStart;
    }

    /** 현재 구간의 출발 기준 시각. 버퍼가 이미 더해진 값이며 호출해도 커서는 움직이지 않는다 */
    public LocalTime reference() {
        return cursor.plusMinutes(STATION_BUFFER_MINUTES);
    }

    /** 이 구간을 확정하고 커서를 뒤 블록 종료 시각까지 옮긴다 */
    public void advance(int travelMinutes, int stayMinutes) {
        cursor = cursor.plusMinutes((long) travelMinutes + stayMinutes);
    }
}
