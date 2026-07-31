package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

import java.util.List;
import java.util.Optional;

public interface PlaceQueryService {

    List<PlaceResDTO.Place> searchPlaces(String query, Double lat, Double lng);

    Optional<PlaceResDTO.Address> reverseGeocode(double lat, double lng);

    Optional<PlaceResDTO.WalkingRoute> getWalkingRoute(double startLat, double startLng, double endLat, double endLng);

    Optional<PlaceResDTO.TaxiRoute> getTaxiRoute(double startLat, double startLng, double endLat, double endLng);
}
