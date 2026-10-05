package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.exception.PaymentInitiationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PaymentAttemptController.class)
public class PaymentInitiationExceptionHandler {
    @ExceptionHandler(PaymentInitiationException.class)
    public ResponseEntity<ApiResponse<Void>> handle(PaymentInitiationException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage()));
    }
}
