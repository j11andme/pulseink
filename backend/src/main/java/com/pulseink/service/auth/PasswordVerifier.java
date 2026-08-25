package com.pulseink.service.auth;

@FunctionalInterface
public interface PasswordVerifier {
    boolean matches(String rawPassword, String encodedPassword);
}
