package com.bcm.service;

import java.security.MessageDigest;
import java.util.HexFormat;

final class PaymentSignature {
    private PaymentSignature() { }

    static boolean matches(String expected, String supplied) {
        if (supplied == null || supplied.length() != expected.length()
                || !supplied.matches("[0-9a-fA-F]+")) { return false; }
        return MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(supplied));
    }
}
