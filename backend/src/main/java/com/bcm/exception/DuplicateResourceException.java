package com.bcm.exception;

/**
 * DuplicateResourceException
 *
 * Throw khi tạo resource bị trùng (email, booking slot, etc.)
 *
 * Example:
 * if (userRepository.existsByEmail(email)) {
 *     throw new DuplicateResourceException("Email already exists: " + email);
 * }
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }

    public DuplicateResourceException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s already exists with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
