package com.bcm.exception;

public class PaymentNotificationException extends RuntimeException {
    public enum Reason { INVALID_SIGNATURE, INVALID_DATA, UNKNOWN_REFERENCE, UNAVAILABLE }
    private final Reason reason;

    public PaymentNotificationException(Reason reason) {
        super("Payment notification rejected: " + reason);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
