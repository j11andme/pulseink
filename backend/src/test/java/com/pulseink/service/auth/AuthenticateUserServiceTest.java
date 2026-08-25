package com.pulseink.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.auth.AccessTokenIssuer.IssuedToken;
import com.pulseink.service.auth.AuthenticateUserUseCase.InvalidCredentialsException;
import com.pulseink.service.auth.AuthenticateUserUseCase.LoginCommand;
import com.pulseink.service.auth.UserAccountRepository.UserAccount;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthenticateUserServiceTest {

    @Test
    void validCredentialsReturnAnAccessTokenAndSafeUserView() {
        var user = new UserAccount(7L, "demo", "$encoded", "EDITOR", true);
        var service = service(
                username -> Optional.of(user),
                (raw, encoded) -> raw.equals("pulseink-demo") && encoded.equals("$encoded"),
                account -> new IssuedToken("jwt-token", 1800L));

        var result = service.authenticate(new LoginCommand(" demo ", "pulseink-demo"));

        assertThat(result.accessToken()).isEqualTo("jwt-token");
        assertThat(result.expiresIn()).isEqualTo(1800L);
        assertThat(result.user().id()).isEqualTo(7L);
        assertThat(result.user().username()).isEqualTo("demo");
        assertThat(result.user().role()).isEqualTo("EDITOR");
    }

    @Test
    void missingUserAndWrongPasswordHaveTheSameFailure() {
        var missingUserService = service(
                username -> Optional.empty(),
                (raw, encoded) -> false,
                account -> {
                    throw new AssertionError("token must not be issued");
                });
        var wrongPasswordService = service(
                username -> Optional.of(new UserAccount(7L, "demo", "$encoded", "EDITOR", true)),
                (raw, encoded) -> false,
                account -> {
                    throw new AssertionError("token must not be issued");
                });

        assertThatThrownBy(() -> missingUserService.authenticate(new LoginCommand("demo", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("invalid username or password");
        assertThatThrownBy(() -> wrongPasswordService.authenticate(new LoginCommand("demo", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("invalid username or password");
    }

    @Test
    void disabledUserCannotAuthenticate() {
        var service = service(
                username -> Optional.of(new UserAccount(7L, "demo", "$encoded", "EDITOR", false)),
                (raw, encoded) -> true,
                account -> {
                    throw new AssertionError("token must not be issued");
                });

        assertThatThrownBy(() -> service.authenticate(new LoginCommand("demo", "pulseink-demo")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("invalid username or password");
    }

    private static AuthenticateUserService service(
            UserAccountRepository repository,
            PasswordVerifier passwordVerifier,
            AccessTokenIssuer accessTokenIssuer) {
        return new AuthenticateUserService(repository, passwordVerifier, accessTokenIssuer);
    }
}
