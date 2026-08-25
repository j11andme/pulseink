package com.pulseink.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

class SecurityErrorHandlerTest {

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unauthenticatedRequestsReceiveTheStableJsonContract() throws Exception {
        var response = new MockHttpServletResponse();

        securityConfig.apiAuthenticationEntryPoint(objectMapper)
                .commence(
                        new MockHttpServletRequest(),
                        response,
                        new AuthenticationException("missing token") {});

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"UNAUTHENTICATED\",\"message\":\"authentication is required\"}");
    }

    @Test
    void forbiddenRequestsReceiveTheStableJsonContract() throws Exception {
        var response = new MockHttpServletResponse();

        securityConfig.apiAccessDeniedHandler(objectMapper)
                .handle(
                        new MockHttpServletRequest(),
                        response,
                        new AccessDeniedException("forbidden"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"ACCESS_DENIED\",\"message\":\"access is denied\"}");
    }

    @Test
    void sseEndpointsFollowTheSameAuthenticationContract() throws Exception {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/runs/1/events");
        request.setMethod("GET");

        securityConfig.apiAuthenticationEntryPoint(objectMapper)
                .commence(
                        request,
                        response,
                        new AuthenticationException("missing token") {});

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"UNAUTHENTICATED\",\"message\":\"authentication is required\"}");
    }
}
