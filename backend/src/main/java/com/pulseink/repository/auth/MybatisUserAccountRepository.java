package com.pulseink.repository.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pulseink.service.auth.UserAccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisUserAccountRepository implements UserAccountRepository {

    private final UserAccountMapper mapper;

    public MybatisUserAccountRepository(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        var entity = mapper.selectOne(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, username)
                .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new UserAccount(
                entity.getId(),
                entity.getUsername(),
                entity.getPasswordHash(),
                entity.getRole(),
                Boolean.TRUE.equals(entity.getEnabled())));
    }
}
