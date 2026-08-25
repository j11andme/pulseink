package com.pulseink.service.auth;

import com.pulseink.service.auth.UserAccountRepository.UserAccount;

@FunctionalInterface
public interface AccessTokenIssuer {

    IssuedToken issue(UserAccount user);

    record IssuedToken(String value, long expiresInSeconds) {}
}
