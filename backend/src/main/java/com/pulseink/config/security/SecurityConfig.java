package com.pulseink.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecretKey jwtSecretKey(AuthProperties properties) {
        var bytes = properties.jwtSecret() == null
                ? new byte[0]
                : properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("PULSEINK_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        return NimbusJwtEncoder.withSecretKey(secretKey).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey secretKey) {
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    ObjectMapper objectMapper() {
        // Jackson 2 mapper for JSON columns and security payloads; Boot 4.1 auto-configures
        // Jackson 3 (tools.jackson) for web serialization, so this bean stays explicit.
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    AuthenticationEntryPoint apiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    new SecurityApiError(
                            "UNAUTHENTICATED",
                            "authentication is required"));
        };
    }

    @Bean
    AccessDeniedHandler apiAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    new SecurityApiError(
                            "ACCESS_DENIED",
                            "access is denied"));
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            AuthenticationEntryPoint apiAuthenticationEntryPoint,
            AccessDeniedHandler apiAccessDeniedHandler) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/campaigns").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/campaigns/*/runs").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/documents").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/documents/*/retry").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/contents/*/versions").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/contents/*/approve").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/contents/*/publications").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/runs/*/insight-candidates").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/insights/*/decision").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/evaluations/runs").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/evaluations/runs/custom").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler)
                        .jwt(jwt ->
                                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    private record SecurityApiError(String code, String message) {}
}
