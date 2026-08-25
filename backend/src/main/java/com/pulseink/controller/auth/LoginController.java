package com.pulseink.controller.auth;

import com.pulseink.service.auth.AuthenticateUserUseCase;
import com.pulseink.service.auth.AuthenticateUserUseCase.LoginCommand;
import com.pulseink.service.auth.AuthenticateUserUseCase.LoginResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final AuthenticateUserUseCase authenticateUserUseCase;

    public LoginController(AuthenticateUserUseCase authenticateUserUseCase) {
        this.authenticateUserUseCase = authenticateUserUseCase;
    }

    @PostMapping("/login")
    public LoginResult login(@Valid @RequestBody LoginRequest request) {
        return authenticateUserUseCase.authenticate(
                new LoginCommand(request.username(), request.password()));
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {}
}
