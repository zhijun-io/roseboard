package com.roseboard.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.setting.PasswordPolicyService;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PasswordService {

    private final UserCredentialMapper credentialsMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;

    public PasswordService(
            UserCredentialMapper credentialsMapper,
            PasswordEncoder passwordEncoder,
            PasswordPolicyService passwordPolicyService) {
        this.credentialsMapper = credentialsMapper;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicyService = passwordPolicyService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserCredentialEntity credentials = credentialsMapper.selectOne(
                new LambdaQueryWrapper<UserCredentialEntity>()
                        .eq(UserCredentialEntity::getUserId, userId));
        if (credentials == null || !passwordEncoder.matches(currentPassword, credentials.getPassword())) {
            throw new BadCredentialsException("Current password does not match");
        }
        passwordPolicyService.validate(newPassword);
        credentials.setPassword(passwordEncoder.encode(newPassword));
        credentials.setFailedLoginAttempts(0);
        credentialsMapper.updateById(credentials);
    }
}
