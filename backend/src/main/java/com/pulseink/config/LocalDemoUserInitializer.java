package com.pulseink.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pulseink.repository.auth.UserAccountEntity;
import com.pulseink.repository.auth.UserAccountMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public final class LocalDemoUserInitializer implements ApplicationRunner {

    private final UserAccountMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    public LocalDemoUserInitializer(
            UserAccountMapper mapper,
            PasswordEncoder passwordEncoder,
            AuthProperties authProperties) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        var existing = mapper.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, "demo"));
        if (existing > 0) {
            return;
        }

        var demo = new UserAccountEntity();
        demo.setUsername("demo");
        demo.setPasswordHash(passwordEncoder.encode(authProperties.demoPassword()));
        demo.setRole("EDITOR");
        demo.setEnabled(true);
        mapper.insert(demo);
    }
}
