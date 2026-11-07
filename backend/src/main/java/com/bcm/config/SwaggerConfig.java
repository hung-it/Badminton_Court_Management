package com.bcm.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SwaggerConfig - API Documentation Configuration
 *
 * Swagger UI URL: http://localhost:8080/api/swagger-ui.html
 * OpenAPI JSON: http://localhost:8080/api/api-docs
 *
 * Features:
 * - Interactive API testing
 * - JWT Bearer token authentication
 * - Request/Response examples
 * - Schema documentation
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        // Security scheme for JWT Bearer Token
        final String securitySchemeName = "Bearer Authentication";

        return new OpenAPI()
                // API Information
                .info(new Info()
                        .title("Badminton Court Management API")
                        .version("1.0.0")
                        .description("REST API for Badminton Court Booking and POS System\n\n" +
                                "Features:\n" +
                                "- JWT Authentication & Authorization\n" +
                                "- Court Booking with Anti-Double Booking\n" +
                                "- 3-Tier Promotion System\n" +
                                "- POS & Inventory Management\n" +
                                "- Payment Integration (VNPay Sandbox)")
                        .contact(new Contact()
                                .name("BCM Development Team")
                                .email("support@bcm.com")
                                .url("https://github.com/your-repo"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))

                // Server Configuration
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080/api")
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.bcm.com")
                                .description("Production Server (TODO)")
                ))

                // Security Configuration - JWT Bearer Token
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter JWT token (without 'Bearer ' prefix)")
                        )
                );
    }
}
