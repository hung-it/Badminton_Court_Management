package com.bcm.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ApiResponse - Standardized API response wrapper
 *
 * Tất cả API endpoints đều trả về format này để đồng nhất
 *
 * Success response example:
 * {
 *   "success": true,
 *   "message": "User created successfully",
 *   "data": { ... },
 *   "timestamp": "2026-10-03T20:30:00"
 * }
 *
 * Error response example:
 * {
 *   "success": false,
 *   "message": "User not found",
 *   "data": null,
 *   "timestamp": "2026-10-03T20:30:00"
 * }
 *
 * @param <T> Type của data trong response
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /**
     * Trạng thái request
     * true = success, false = error
     */
    private boolean success;

    /**
     * Message mô tả kết quả
     */
    private String message;

    /**
     * Data trả về (có thể là object, list, hoặc null)
     */
    private T data;

    /**
     * Timestamp khi response được tạo
     */
    private LocalDateTime timestamp;

    // ==========================================
    // Static Factory Methods - Success
    // ==========================================

    /**
     * Success response với data
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Success", data, LocalDateTime.now());
    }

    /**
     * Success response với custom message và data
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, LocalDateTime.now());
    }

    /**
     * Success response không có data (VD: DELETE thành công)
     */
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, message, null, LocalDateTime.now());
    }

    // ==========================================
    // Static Factory Methods - Error
    // ==========================================

    /**
     * Error response với message
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, LocalDateTime.now());
    }

    /**
     * Error response với message và error details
     */
    public static <T> ApiResponse<T> error(String message, T errorDetails) {
        return new ApiResponse<>(false, message, errorDetails, LocalDateTime.now());
    }
}
