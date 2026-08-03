package com.ssafy.ieumgil.domain.transit.exception;

import com.ssafy.ieumgil.global.exception.CustomException;

public class TransitException extends CustomException {

    public TransitException(TransitErrorCode code) {
        super(code);
    }
}
