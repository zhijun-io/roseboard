package com.roseboard.setting.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.SecurityUserService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DefaultSecurityUserService implements SecurityUserService {

    private final UserMapper userMapper;
    private final UserCredentialMapper credentialsMapper;

    public DefaultSecurityUserService(UserMapper userMapper, UserCredentialMapper credentialsMapper) {
        this.userMapper = userMapper;
        this.credentialsMapper = credentialsMapper;
    }

    @Override
    public SecurityUser loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity user = userMapper.findByEmail(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }
        UserCredentialEntity credentials = credentialsMapper.selectOne(
                new LambdaQueryWrapper<UserCredentialEntity>()
                        .eq(UserCredentialEntity::getUserId, user.getId()));
        if (credentials == null) {
            throw new UsernameNotFoundException("User credentials not found");
        }
        return principal(user, credentials);
    }
    public SecurityUser loadUserById(UUID userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) throw new UsernameNotFoundException("User not found");
        UserCredentialEntity credentials = credentialsMapper.selectOne(
                new LambdaQueryWrapper<UserCredentialEntity>()
                        .eq(UserCredentialEntity::getUserId, user.getId()));
        if (credentials == null) throw new UsernameNotFoundException("User credentials not found");
        return principal(user, credentials);
    }

    private SecurityUser principal(UserEntity user, UserCredentialEntity credentials) {
        return new SecurityUser(user.getId(), user.getTenantId(), user.getCustomerId(),
                user.getEmail(), credentials.getPassword(),
                user.getAuthority() == null ? null : user.getAuthority().name(),
                Boolean.TRUE.equals(credentials.getEnabled()));
    }
}
