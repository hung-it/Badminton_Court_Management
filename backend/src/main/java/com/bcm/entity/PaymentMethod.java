package com.bcm.entity;

public enum PaymentMethod {
    CASH,
    VNPAY,
    // Mapping only for protected historical rows; never accepted for new payments.
    BANK_TRANSFER
}
