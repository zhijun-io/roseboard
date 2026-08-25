package com.roseboard.infrastructure.notification.channel;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ChannelSendRequest;
import com.roseboard.infrastructure.notification.model.ConfigScope;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.notification.model.ConnectivityStatus;
import com.roseboard.infrastructure.notification.model.DeliveryOutcome;
import com.roseboard.infrastructure.notification.model.RenderedChannelContent;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import com.roseboard.infrastructure.notification.internal.RecipientDelivery;
import com.roseboard.infrastructure.notification.spi.NotificationChannelSender;
import com.roseboard.infrastructure.notification.spi.NotificationUsageMeter;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

public class EmailNotificationSender implements NotificationChannelSender {
    private final SmtpMailClient mailSender;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<NotificationUsageMeter> usageMeter;
    private final String configuredFrom;

    public EmailNotificationSender(SmtpMailClient mailSender, ObjectMapper objectMapper,
                              ObjectProvider<NotificationUsageMeter> usageMeter,
                              @Value("${roseboard.mail.from:noreply@roseboard.local}") String configuredFrom) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.usageMeter = usageMeter;
        this.configuredFrom = configuredFrom;
    }

    @Override
    public ChannelKind kind() {
        return ChannelKind.EMAIL;
    }

    @Override
    public DeliveryOutcome send(ChannelSendRequest request) {
        RenderedChannelContent rendered = request.rendered();
        if (rendered == null) {
            return DeliveryOutcome.skipped("No rendered EMAIL content");
        }
        JsonNode mailConfig = SmtpMailConfiguration.toSmtpMailClientJson(request.channelConfig(), objectMapper);
        return RecipientDelivery.deliver(
                request.recipients(),
                recipient -> StringUtils.hasText(recipient.email()),
                recipient -> {
                    sendConfigured(mailConfig, recipient.email(), rendered);
                    if (request.countUsage()) {
                        recordUsage(request.tenantId());
                    }
                },
                "No email recipients");
    }

    @Override
    public ConnectivityResult verifyConnectivity(ResolvedChannelConfig config) {
        if (config == null || !config.enabled()) {
            return new ConnectivityResult(ChannelKind.EMAIL, config == null ? ConfigScope.PLATFORM
                    : config.scope(), ConnectivityStatus.DISABLED, null);
        }
        if (!config.isComplete()) {
            return new ConnectivityResult(ChannelKind.EMAIL, config.scope(),
                    ConnectivityStatus.NOT_CONFIGURED, "SMTP is not configured");
        }
        try {
            mailSender.updateMailConfiguration(SmtpMailConfiguration.toSmtpMailClientJson(config, objectMapper));
            mailSender.testConnection();
            return new ConnectivityResult(ChannelKind.EMAIL, config.scope(), ConnectivityStatus.OK, null);
        } catch (RuntimeException | MessagingException exception) {
            return new ConnectivityResult(ChannelKind.EMAIL, config.scope(), ConnectivityStatus.FAILED,
                    exception.getMessage());
        }
    }

    private void sendConfigured(JsonNode mailConfig, String recipient, RenderedChannelContent rendered) {
        mailSender.updateMailConfiguration(mailConfig);
        String from = mailConfig.path("mailFrom").asText(configuredFrom);
        if (from.isBlank()) {
            from = configuredFrom;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(rendered.subject() == null ? "" : rendered.subject());
            helper.setText(rendered.body() == null ? "" : rendered.body(), rendered.html());
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("Failed to send email", exception);
        }
    }

    private void recordUsage(UUID tenantId) {
        NotificationUsageMeter usage = usageMeter.getIfAvailable();
        if (tenantId != null && usage != null) {
            usage.record(tenantId, ChannelKind.EMAIL);
        }
    }
}
