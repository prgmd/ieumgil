package com.ssafy.ieumgil.domain.festival.exception;

import com.ssafy.ieumgil.global.exception.CustomException;

public class FestivalException extends CustomException {

    public FestivalException(FestivalErrorCode code) {
        super(code);
    }
}
