package com.roseboard.infrastructure.audit;

import com.roseboard.common.JacksonUtils;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.writer.AuditWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 默认 {@link AuditTemplate}：尽力落库；失败只记 warn，不影响业务。
 */
public class DefaultAuditTemplate implements AuditTemplate {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditTemplate.class);

    private final AuditWriter writer;

    public DefaultAuditTemplate(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    public void record(String action, SecurityUsers principal, AuditTarget target, AuditStatus status,
                       AuditContext context, String detailJson, String failureMessage, Long durationMs) {
        try {
            JsonNode safeDetail = DefaultAuditDetailsSanitizer.sanitize(parseDetail(detailJson));
            AuditRecord record = AuditRecord.create(action, principal, target, status, safeDetail,
                    failureMessage, context, durationMs);
            writer.write(record);
        } catch (RuntimeException failure) {
            log.warn("audit record failed and was isolated from business flow: action={}", action, failure);
        }
    }

    private static JsonNode parseDetail(String detailJson) {
        return detailJson == null ? null : JacksonUtils.toJsonNode(detailJson);
    }
}
