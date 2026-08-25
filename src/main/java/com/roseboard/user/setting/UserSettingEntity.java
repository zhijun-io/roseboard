package com.roseboard.user.setting;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "user_setting", autoResultMap = true)
public class UserSettingEntity {
    @TableId
    private UUID userId;
    private String key;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode value;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
}
