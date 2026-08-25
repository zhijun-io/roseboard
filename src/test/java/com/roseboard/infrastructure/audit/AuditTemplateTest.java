package com.roseboard.infrastructure.audit;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditEventListener;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.audit.writer.AuditWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证框架记录器：构造领域事件并委托 {@link AuditWriter}，
 * detail 经默认策略脱敏，context 由默认 web provider 自动构建。
 */
@ExtendWith(MockitoExtension.class)
class AuditTemplateTest {

    @Mock
    AuditWriter writer;

    private AuditTemplate template() {
        return new DefaultAuditTemplate(writer);
    }

    @Test
    void recordDelegatesSanitizedEventWithoutWebContext() {
        AuditTemplate template = template();
        SecurityUsers principal = SecurityUsers.of(UUID.randomUUID(), UUID.randomUUID(), null, "tester");

        template.record(AuditActions.USER_CREATED, principal, AuditTarget.user(UUID.randomUUID()),
                AuditStatus.SUCCEEDED, AuditContext.empty(),
                "{\"visible\":\"kept\",\"password\":\"hidden\",\"nested\":{\"clientSecret\":\"x\"}}",
                null, null);

        ArgumentCaptor<AuditRecord> captured = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captured.capture());
        AuditRecord record = captured.getValue();
        assertEquals(AuditActions.USER_CREATED, record.action());
        assertEquals("kept", record.detail().get("visible").asText());
        assertNull(record.detail().get("password"), "sensitive field must be removed");
        assertNull(record.detail().get("nested").get("clientSecret"), "nested sensitive field must be removed");
        assertTrue(record.context().isEmpty(), "non-web thread context is empty");
        assertNull(record.failureMessage());
    }

    @Test
    void failedRecordCarriesExplicitFailureDetailsAndNoSecrets() {
        AuditTemplate template = template();

        template.record(AuditActions.USER_DELETED, SecurityUsers.of(UUID.randomUUID(), null, null),
                AuditTarget.user(UUID.randomUUID()), AuditStatus.FAILED, AuditContext.empty(),
                "{\"apiKey\":\"hidden-key\",\"failure\":\"invalid password\"}",
                "invalid password", null);

        ArgumentCaptor<AuditRecord> captured = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captured.capture());
        AuditRecord record = captured.getValue();
        assertEquals(AuditStatus.FAILED, record.status());
        assertNull(record.detail().get("apiKey"));
        assertEquals("invalid password", record.detail().get("failure").asText(),
                "non-sensitive failure field is kept in detail");
        assertEquals("invalid password", record.failureMessage());
    }

    @Test
    void recordCarriesExplicitNonWebContextAndFailureMessage() {
        AuditTemplate template = template();

        template.record(AuditActions.USER_DELETED, SecurityUsers.anonymous(),
                AuditTarget.user(UUID.randomUUID()), AuditStatus.FAILED,
                AuditContext.of(AuditOrigin.MESSAGE, "message-42",
                        java.util.Map.of("consumer", "user-events")),
                null, "rejected", 12L);

        ArgumentCaptor<AuditRecord> captured = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captured.capture());
        AuditRecord record = captured.getValue();
        assertEquals(AuditOrigin.MESSAGE, record.context().origin());
        assertEquals("message-42", record.context().requestId());
    }

    @ParameterizedTest
    @EnumSource(value = AuditOrigin.class, names = {"MQTT", "MESSAGE", "SCHEDULED", "INTERNAL"})
    void recordCarriesNonHttpOriginAndOptionalEntityName(AuditOrigin origin) {
        AuditTemplate template = template();
        UUID entityId = UUID.randomUUID();

        template.record(AuditActions.USER_CREATED, SecurityUsers.anonymous(),
                AuditTarget.of(EntityType.USER, entityId, "device-owner"),
                AuditStatus.SUCCEEDED,
                AuditContext.of(origin, origin.name().toLowerCase() + "-42",
                        java.util.Map.of("source", origin.name().toLowerCase())),
                null, null, null);

        ArgumentCaptor<AuditRecord> captured = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captured.capture());
        AuditRecord record = captured.getValue();
        assertEquals(origin, record.context().origin());
        assertEquals(origin.name().toLowerCase() + "-42", record.context().requestId());
        assertEquals("device-owner", record.target().entityName());
    }

    @Test
    void missingEntityNameRemainsNullInsteadOfDuplicatingEntityType() {
        assertNull(AuditTarget.of(EntityType.USER, UUID.randomUUID()).entityName());
        assertNull(AuditTarget.of(EntityType.USER, UUID.randomUUID(), "  ").entityName());
    }

    @Test
    void missingEventContextInheritsProviderButExplicitEmptyContextRemainsInternal() {
        AuditContextProvider provider = () -> AuditContext.of(
                AuditOrigin.HTTP, "request-42", java.util.Map.of());
        AuditEventListener listener = new AuditEventListener(template(), provider);

        listener.onAuditEvent(AuditEvent.success(
                AuditActions.USER_CREATED, SecurityUsers.anonymous(),
                AuditTarget.user(UUID.randomUUID())));
        listener.onAuditEvent(AuditEvent.success(
                AuditActions.USER_CREATED, SecurityUsers.anonymous(),
                AuditTarget.user(UUID.randomUUID()), null, AuditContext.empty()));

        ArgumentCaptor<AuditRecord> captured = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer, org.mockito.Mockito.times(2)).write(captured.capture());
        assertEquals(AuditOrigin.HTTP, captured.getAllValues().get(0).context().origin());
        assertEquals(AuditOrigin.INTERNAL, captured.getAllValues().get(1).context().origin());
    }

    @Test
    void invalidDetailJsonIsIsolatedBeforeWrite() {
        AuditTemplate template = template();
        template.record(AuditActions.USER_CREATED, SecurityUsers.anonymous(),
                AuditTarget.user(UUID.randomUUID()), AuditStatus.SUCCEEDED, AuditContext.empty(),
                "{not-json", null, null);
        verifyNoInteractions(writer);
    }

    @Test
    void invalidActionIsIsolatedBeforeWrite() {
        AuditTemplate template = template();
        template.record("   ", SecurityUsers.of(UUID.randomUUID(), null, null),
                AuditTarget.user(UUID.randomUUID()), AuditStatus.SUCCEEDED, AuditContext.empty(),
                null, null, null);
        verifyNoInteractions(writer);
    }

    @Test
    void writerFailureIsIsolatedAndDoesNotPropagate() {
        AuditTemplate template = template();
        org.mockito.Mockito.doThrow(new RuntimeException("storage down"))
                .when(writer).write(org.mockito.ArgumentMatchers.any());
        template.record(AuditActions.USER_CREATED, SecurityUsers.of(UUID.randomUUID(), null, null),
                AuditTarget.user(UUID.randomUUID()), AuditStatus.SUCCEEDED, AuditContext.empty(),
                null, null, null);
        verify(writer).write(org.mockito.ArgumentMatchers.any(AuditRecord.class));
    }

    @Test
    void oversizedDetailIsIsolated() {
        AuditTemplate template = template();

        template.record(AuditActions.USER_CREATED, SecurityUsers.anonymous(),
                AuditTarget.user(UUID.randomUUID()), AuditStatus.SUCCEEDED, AuditContext.empty(),
                "{\"payload\":\"" + "x".repeat(16 * 1024) + "\"}", null, null);

        verifyNoInteractions(writer);
    }
}
