package com.bcm.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Only safe, server-authored messages; never includes provider bodies, signatures or credentials. */
@Getter
public class PaymentInitiationException extends RuntimeException {
    private final HttpStatus status;

    public PaymentInitiationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
