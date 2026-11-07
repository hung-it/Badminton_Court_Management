package com.bcm.dto.request;

import jakarta.validation.constraints.Size;

public record BookingPromotionRequest(@Size(max = 50) String code) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectOtherFields(String field, Object ignored) {
        throw new IllegalArgumentException("Only voucher code is accepted");
    }
}
