package com.ssafy.ieumgil.domain.place.exception;

import com.ssafy.ieumgil.global.exception.CustomException;

public class PlaceException extends CustomException {

    public PlaceException(PlaceErrorCode code) {
        super(code);
    }
}
