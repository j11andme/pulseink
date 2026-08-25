package com.pulseink.client.auth;

import com.pulseink.service.auth.AccessTokenIssuer;
import com.pulseink.service.auth.UserAccountRepository.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

@Component
public final class JwtTokenAdapter implements AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final Duration tokenTtl;

    public JwtTokenAdapter(
            JwtEncoder jwtEncoder,
            @Value("${pulseink.auth.token-ttl:PT30M}") Duration tokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.tokenTtl = tokenTtl;
    }

    @Override
    public IssuedToken issue(UserAccount user) {
        var issuedAt = Instant.now();
        var expiresAt = issuedAt.plus(tokenTtl);
        var claims = JwtClaimsSet.builder()
                .subject(user.username())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("uid", user.id())
                .claim("roles", List.of(user.role()))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
        return new IssuedToken(token.getTokenValue(), tokenTtl.toSeconds());
    }
}
