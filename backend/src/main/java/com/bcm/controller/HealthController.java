package com.bcm.controller;

import com.bcm.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Controller
 * Simple endpoint to verify API is running
 */
@RestController
@RequestMapping("/health")
@Tag(name = "Health Check", description = "System health and status endpoints")
public class HealthController {

    @GetMapping
    @Operation(
        summary = "Check API Health",
        description = "Returns basic health status and system information"
    )
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> healthInfo = new HashMap<>();
        healthInfo.put("status", "UP");
        healthInfo.put("timestamp", LocalDateTime.now());
        healthInfo.put("service", "Badminton Court Management API");
        healthInfo.put("version", "1.0.0");
        healthInfo.put("database", "PostgreSQL - Connected");

        return ApiResponse.success(healthInfo, "System is healthy");
    }

    @GetMapping("/ping")
    @Operation(
        summary = "Simple Ping",
        description = "Minimal endpoint for uptime monitoring"
    )
    public ApiResponse<String> ping() {
        return ApiResponse.success("pong", "API is responding");
    }
}
