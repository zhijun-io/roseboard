package com.roseboard.common.security;

import com.roseboard.infrastructure.audit.aspect.AuditAspect;

/**
 * 审计主体的提取端口。audit 模块不依赖 security：实现方（如
 * {@code infrastructure.security} 包里的 RoseboardAuditPrincipalProvider）
 * 负责从安全上下文解析当前用户并提供给 {@link AuditAspect}。
 */
public interface SecurityUserProvider {

    SecurityUsers currentUser();
}
