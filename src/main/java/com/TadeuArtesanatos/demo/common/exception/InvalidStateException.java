package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateException extends BusinessException {

    public InvalidStateException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
