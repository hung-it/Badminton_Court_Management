package com.bcm.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VnPayIpnAcknowledgment(@JsonProperty("RspCode") String code,
                                   @JsonProperty("Message") String message) {
    public static VnPayIpnAcknowledgment of(String code) {
        return new VnPayIpnAcknowledgment(code, switch (code) {
            case "00" -> "Confirm Success";
            case "01" -> "Order not found";
            case "02" -> "Order already confirmed";
            case "04" -> "invalid amount";
            case "97" -> "Invalid signature";
            default -> "Unknow error";
        });
    }
}
