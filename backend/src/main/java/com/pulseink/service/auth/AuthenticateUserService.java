package com.pulseink.service.auth;

import com.pulseink.service.auth.AuthenticateUserUseCase.InvalidCredentialsException;
import com.pulseink.service.auth.AuthenticateUserUseCase.LoginCommand;
import com.pulseink.service.auth.AuthenticateUserUseCase.LoginResult;
import com.pulseink.service.auth.AuthenticateUserUseCase.UserView;
import java.util.Objects;

public final class AuthenticateUserService implements AuthenticateUserUseCase {

    private final UserAccountRepository userAccountRepository;
    private final PasswordVerifier passwordVerifier;
    private final AccessTokenIssuer accessTokenIssuer;

    public AuthenticateUserService(
            UserAccountRepository userAccountRepository,
            PasswordVerifier passwordVerifier,
            AccessTokenIssuer accessTokenIssuer) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository);
        this.passwordVerifier = Objects.requireNonNull(passwordVerifier);
        this.accessTokenIssuer = Objects.requireNonNull(accessTokenIssuer);
    }

    @Override
    public LoginResult authenticate(LoginCommand command) {
        if (command == null
                || command.username() == null
                || command.username().isBlank()
                || command.password() == null
                || command.password().isBlank()) {
            throw new InvalidCredentialsException();
        }

        var username = command.username().trim();
        var user = userAccountRepository.findByUsername(username)
                .filter(UserAccountRepository.UserAccount::enabled)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordVerifier.matches(command.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        var token = accessTokenIssuer.issue(user);
        return new LoginResult(
                token.value(),
                token.expiresInSeconds(),
                new UserView(user.id(), user.username(), user.role()));
    }
}
