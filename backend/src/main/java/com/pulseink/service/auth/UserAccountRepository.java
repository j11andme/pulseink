package com.pulseink.service.auth;

import java.util.Optional;

public interface UserAccountRepository {

    Optional<UserAccount> findByUsername(String username);

    record UserAccount(
            long id,
            String username,
            String passwordHash,
            String role,
            boolean enabled) {}
}
