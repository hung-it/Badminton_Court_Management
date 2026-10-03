package com.bcm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CorsConfig - CORS (Cross-Origin Resource Sharing) Configuration
 *
 * Cho phép frontend (React app) ở domain khác gọi API
 *
 * Development:
 * - Vite dev server: http://localhost:5173
 * - React dev server: http://localhost:3000
 *
 * Production:
 * - Thay bằng domain thật (VD: https://badminton-app.com)
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // Cho phép gửi credentials (cookies, authorization headers)
        config.setAllowCredentials(true);

        // Cho phép origins (frontend URLs)
        config.setAllowedOrigins(Arrays.asList(
                "http://localhost:5173",  // Vite dev server
                "http://localhost:3000"   // React dev server
                // TODO: Thêm production domain khi deploy
        ));

        // Cho phép tất cả headers
        config.addAllowedHeader("*");

        // Cho phép tất cả HTTP methods (GET, POST, PUT, DELETE, etc.)
        config.addAllowedMethod("*");

        // Expose headers cho frontend đọc được
        config.setExposedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Total-Count"  // Cho pagination
        ));

        // Apply config cho tất cả endpoints /api/**
        source.registerCorsConfiguration("/api/**", config);

        return new CorsFilter(source);
    }
}
