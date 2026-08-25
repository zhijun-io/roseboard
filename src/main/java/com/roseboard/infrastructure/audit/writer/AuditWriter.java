package com.roseboard.infrastructure.audit.writer;

import com.roseboard.infrastructure.audit.AuditRecord;

/**
 * 审计持久化端口。框架只产生 {@link AuditRecord}，默认写 JDBC {@code audit_log}；
 * 可替换为 Kafka/远程审计服务，替换方负责自己的交付保证，接口不承诺事务语义。
 */
public interface AuditWriter {

    void write(AuditRecord record);
}
