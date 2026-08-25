package com.roseboard.infrastructure.audit.event;

/**
 * 审计事件的业务来源。这里描述事件从哪里进入系统，不描述底层 Web 实现技术。
 */
public enum AuditOrigin {
    HTTP,
    MQTT,
    MESSAGE,
    SCHEDULED,
    INTERNAL
}
