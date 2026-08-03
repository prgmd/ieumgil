package com.ssafy.ieumgil.domain.chatbot.tool;

public record TaxiRouteResult(
        String startPlaceName, String endPlaceName,
        boolean found, Integer fare, Integer distanceM, Integer durationMin
) {

    public static TaxiRouteResult of(String start, String end, int fare, int distanceM, int durationMin) {
        return new TaxiRouteResult(start, end, true, fare, distanceM, durationMin);
    }

    public static TaxiRouteResult empty(String start, String end) {
        return new TaxiRouteResult(start, end, false, null, null, null);
    }
}
