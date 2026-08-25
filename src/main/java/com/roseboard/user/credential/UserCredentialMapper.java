package com.roseboard.user.credential;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface UserCredentialMapper extends BaseMapper<UserCredentialEntity> {
    default int updatePasswordAndClearResetToken(UUID id, String password) {
        return update(null, new LambdaUpdateWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getId, id)
                .set(UserCredentialEntity::getPassword, password)
                .set(UserCredentialEntity::getResetToken, null)
                .set(UserCredentialEntity::getResetTokenExpTime, null)
                .set(UserCredentialEntity::getFailedLoginAttempts, 0));
    }
    default UserCredentialEntity findByUserId(UUID userId) {
        return selectOne(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getUserId, userId));
    }

    default UserCredentialEntity findByActivateToken(String activateToken) {
        return selectOne(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getActivateToken, activateToken));
    }

    default UserCredentialEntity findByResetToken(String resetToken) {
        return selectOne(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getResetToken, resetToken));
    }

    default int deleteByUserId(UUID userId) {
        return delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getUserId, userId));
    }
}
