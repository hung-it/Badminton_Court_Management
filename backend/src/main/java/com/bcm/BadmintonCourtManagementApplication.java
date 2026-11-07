package com.bcm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Main Application Class
 * Badminton Court Management System
 *
 * @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan
 * @EnableJpaAuditing = Enable JPA Auditing (created_at, updated_at auto-populate)
 */
@SpringBootApplication
@EnableJpaAuditing
public class BadmintonCourtManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(BadmintonCourtManagementApplication.class, args);

        System.out.println("\n" +
            "========================================\n" +
            "  Badminton Court Management API\n" +
            "  Status: RUNNING\n" +
            "  Port: 8080\n" +
            "  Context Path: /api\n" +
            "  Swagger UI: http://localhost:8080/api/swagger-ui.html\n" +
            "========================================\n"
        );
    }
}
