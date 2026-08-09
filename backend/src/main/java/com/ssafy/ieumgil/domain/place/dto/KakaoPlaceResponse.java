package com.ssafy.ieumgil.domain.place.dto;

import java.util.List;

public record KakaoPlaceResponse(List<Document> documents) {

    public record Document(
            String id,
            String place_name,
            /** 카테고리 계층 전체. "음식점 > 카페 > 커피전문점 > 스타벅스" 처럼 마지막에 브랜드가 온다 */
            String category_name,
            String category_group_name,
            String category_group_code,
            String address_name,
            String road_address_name,
            String phone,
            String x,
            String y
    ) {
    }
}
