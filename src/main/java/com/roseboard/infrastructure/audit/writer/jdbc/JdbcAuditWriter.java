package com.roseboard.infrastructure.audit.writer.jdbc;

import com.roseboard.infrastructure.audit.AuditRecord;
import com.roseboard.infrastructure.audit.writer.AuditWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * 默认 {@link AuditWriter}：将审计事件写入 {@code audit_log} 表。
 * 由 {@code AuditAutoConfiguration} 显式装配，不注册为组件扫描 bean。
 */
public class JdbcAuditWriter implements AuditWriter {

    private static final String INSERT_SQL = """
            insert into audit_log (id, occurred_at, actor_user_id, actor_name,
                tenant_id, customer_id, action, entity_type, entity_id, entity_name, status,
                origin, request_id, failure_message,
                detail, context, duration_ms)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do nothing
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(AuditRecord record) {
        jdbcTemplate.update(INSERT_SQL, statement -> bind(statement, record));
    }

    private void bind(PreparedStatement statement, AuditRecord record) throws SQLException {
        statement.setObject(1, record.id());
        statement.setTimestamp(2, Timestamp.from(record.occurredAt()));
        setUuid(statement, 3, record.principal().getUserId());
        statement.setString(4, record.principal().getUserName());
        setUuid(statement, 5, record.target().tenantId());
        setUuid(statement, 6, record.target().customerId());
        statement.setString(7, record.action());
        statement.setString(8, record.target().entityType().name());
        setUuid(statement, 9, record.target().entityId());
        statement.setString(10, record.target().entityName());
        statement.setString(11, record.status().name());
        statement.setString(12, record.context().origin().name());
        setString(statement, 13, record.context().requestId());
        setString(statement, 14, record.failureMessage());
        setJson(statement, 15, record.detail());
        setJson(statement, 16, record.context().attributesNode());
        setLong(statement, 17, record.durationMs());
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    private static void setString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setJson(PreparedStatement statement, int index, JsonNode value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value.toString(), Types.OTHER);
        }
    }
}
