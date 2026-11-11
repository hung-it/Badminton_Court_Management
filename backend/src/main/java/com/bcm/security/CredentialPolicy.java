package com.bcm.security;

import com.bcm.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared rules for new credentials; existing password hashes remain valid. */
public final class CredentialPolicy {
    private CredentialPolicy() { }

    public static String email(String value) { return value.strip().toLowerCase(Locale.ROOT); }

    public static void requireNewPassword(String value) {
        if (value == null || value.isBlank() || value.length() < 8 || value.length() > 72
                || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BadRequestException("Mật khẩu mới phải có ít nhất 8 ký tự và không quá 72 byte UTF-8.");
    }

    public static void requireCurrentPassword(String value) {
        if (value == null || value.isBlank() || value.length() > 100)
            throw new BadRequestException("Mật khẩu hiện tại không hợp lệ.");
    }
}
