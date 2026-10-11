package com.bcm.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void preflightMatchesServletPathAfterApiContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/bookings");
        request.setContextPath("/api");
        request.setServletPath("/bookings");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization,content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorsConfig().corsFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }
}
