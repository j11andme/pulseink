package com.pulseink.service.auth;

public interface AuthenticateUserUseCase {

    LoginResult authenticate(LoginCommand command);

    record LoginCommand(String username, String password) {}

    record LoginResult(String accessToken, long expiresIn, UserView user) {}

    record UserView(long id, String username, String role) {}

    final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("invalid username or password");
        }
    }
}
