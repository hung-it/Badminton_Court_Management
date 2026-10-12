package com.bcm.service;

import com.bcm.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Authenticated provider result, runtime only; never a browser payment authority. */
public record PaymentNotification(UUID attemptId, PaymentMethod method, BigDecimal amount,
                                  boolean providerSuccess, String transactionId, LocalDateTime transactionDate) { }
