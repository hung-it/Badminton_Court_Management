package com.bcm.exception;

/**
 * BadRequestException
 *
 * Throw khi request không hợp lệ (business logic violation)
 *
 * Example:
 * if (booking.getExpiresAt().isBefore(LocalDateTime.now())) {
 *     throw new BadRequestException("Booking has expired");
 * }
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
