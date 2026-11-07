package com.bcm.exception;

/**
 * ResourceNotFoundException
 *
 * Throw khi không tìm thấy resource (User, Booking, Product, etc.)
 *
 * Example:
 * User user = userRepository.findById(id)
 *     .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
