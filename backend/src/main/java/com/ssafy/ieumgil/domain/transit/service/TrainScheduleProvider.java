package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;

import java.util.List;

public interface TrainScheduleProvider {

    List<TransitScheduleResDTO.TrainSchedule> findSchedule(String departureName, String arrivalName);
}
