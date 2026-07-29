package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.client.KakaoLocalClient;
import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlaceQueryServiceImpl implements PlaceQueryService {

    private static final int MAX_RESULTS = 5;

    private final KakaoLocalClient kakaoLocalClient;

    @Override
    public List<PlaceResDTO.Place> searchPlaces(String query, Double lat, Double lng) {
        return kakaoLocalClient.searchByKeyword(query, lat, lng).stream()
                .limit(MAX_RESULTS)
                .map(this::toPlace)
                .toList();
    }

    @Override
    public Optional<PlaceResDTO.Address> reverseGeocode(double lat, double lng) {
        return kakaoLocalClient.coord2Address(lat, lng).map(this::toAddress);
    }

    @Override
    public Optional<PlaceResDTO.WalkingRoute> getWalkingRoute(
            double startLat, double startLng, double endLat, double endLng) {
        return kakaoLocalClient.getWalkingRoute(startLat, startLng, endLat, endLng)
                .map(r -> new PlaceResDTO.WalkingRoute(r.totalDistance(), r.totalTime()));
    }

    private PlaceResDTO.Place toPlace(KakaoPlaceResponse.Document d) {
        String address = (d.road_address_name() != null && !d.road_address_name().isBlank())
                ? d.road_address_name() : d.address_name();
        return PlaceResDTO.Place.builder()
                .placeId(d.id())
                .name(d.place_name())
                .address(address)
                .lat(Double.parseDouble(d.y()))
                .lng(Double.parseDouble(d.x()))
                .category(d.category_group_name())
                .build();
    }

    private PlaceResDTO.Address toAddress(KakaoAddressResponse.Document d) {
        String address = d.address() != null ? d.address().address_name() : null;
        String roadAddress = d.road_address() != null ? d.road_address().address_name() : null;
        return new PlaceResDTO.Address(address, roadAddress);
    }
}
