package com.ssafy.ieumgil.domain.chatbot.tool;

public record WalkingRouteResult(
        String startPlaceName, String endPlaceName,
        boolean found, Integer distanceM, Integer durationMin
) {

    public static WalkingRouteResult of(String start, String end, int distanceM, int durationMin) {
        return new WalkingRouteResult(start, end, true, distanceM, durationMin);
    }

    public static WalkingRouteResult empty(String start, String end) {
        return new WalkingRouteResult(start, end, false, null, null);
    }
}
