package com.roseboard.infrastructure.notification.internal;

import com.roseboard.infrastructure.notification.spi.NotificationChannelSender;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.infrastructure.notification.spi.NotificationChannelConfigResolver;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateRenderer;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateSource;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ChannelSendRequest;
import com.roseboard.infrastructure.notification.model.ConfigScope;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.notification.model.ConnectivityStatus;
import com.roseboard.infrastructure.notification.model.DeliveryOutcome;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyResult;
import com.roseboard.infrastructure.notification.model.RenderedTemplate;
import com.roseboard.infrastructure.notification.model.RenderedChannelContent;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import com.roseboard.infrastructure.notification.model.TemplateDefinition;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class DefaultNotificationCenter implements NotificationCenter {
    private final NotificationTemplateSource templateSource;
    private final NotificationTemplateRenderer templateRenderer;
    private final NotificationChannelConfigResolver channelConfigSource;
    private final Map<ChannelKind, NotificationChannelSender> channelSenders;

    public DefaultNotificationCenter(NotificationTemplateSource templateSource,
                                     NotificationTemplateRenderer templateRenderer,
                                     NotificationChannelConfigResolver channelConfigSource,
                                     List<NotificationChannelSender> senders) {
        this.templateSource = templateSource;
        this.templateRenderer = templateRenderer;
        this.channelConfigSource = channelConfigSource;
        this.channelSenders = new EnumMap<>(ChannelKind.class);
        for (NotificationChannelSender sender : senders) {
            this.channelSenders.put(sender.kind(), sender);
        }
    }

    @Override
    public NotifyResult notify(UUID tenantId, NotifyCommand command) {
        TemplateDefinition template = templateSource.find(tenantId, command.templateKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Notification template not found: " + command.templateKey()));
        String notificationType = command.type() == null || command.type().isBlank()
                ? template.notificationType() : command.type();
        RenderedTemplate rendered = templateRenderer.render(template, command.vars(), command.channels());
        Map<ChannelKind, DeliveryOutcome> outcomes = new LinkedHashMap<>();
        for (ChannelKind kind : command.channels()) {
            outcomes.put(kind, deliverChannel(tenantId, kind, command, notificationType, rendered));
        }
        return new NotifyResult(outcomes);
    }

    @Override
    public ConnectivityResult verifyConnectivity(UUID tenantId, ChannelKind kind) {
        Optional<ResolvedChannelConfig> config = channelConfigSource.resolve(tenantId, kind);
        NotificationChannelSender sender = channelSenders.get(kind);
        if (sender == null) {
            return new ConnectivityResult(kind, ConfigScope.PLATFORM, ConnectivityStatus.NOT_CONFIGURED,
                    "No sender registered for " + kind);
        }
        return sender.verifyConnectivity(config.orElse(null));
    }

    @Override
    public List<ChannelKind> availableChannels(UUID tenantId) {
        return channelSenders.keySet().stream()
                .filter(kind -> channelConfigSource.resolve(tenantId, kind)
                        .map(config -> config.enabled() && (kind == ChannelKind.WEB || config.isComplete()))
                        .orElse(false))
                .toList();
    }

    private DeliveryOutcome deliverChannel(UUID tenantId, ChannelKind kind, NotifyCommand command,
                                           String notificationType, RenderedTemplate rendered) {
        NotificationChannelSender sender = channelSenders.get(kind);
        if (sender == null) {
            return DeliveryOutcome.notConfigured("No sender registered for " + kind);
        }
        Optional<ResolvedChannelConfig> configOptional = channelConfigSource.resolve(tenantId, kind);
        if (configOptional.isEmpty()) {
            return DeliveryOutcome.notConfigured(kind + " is not configured");
        }
        ResolvedChannelConfig config = configOptional.get();
        if (!config.enabled() && !command.options().ignoreTenantChannelDisabled()) {
            return DeliveryOutcome.disabled();
        }
        if (!config.isComplete() && kind != ChannelKind.WEB) {
            return DeliveryOutcome.notConfigured(kind + " configuration is incomplete");
        }
        String renderError = rendered.renderError(kind);
        if (renderError != null) {
            return DeliveryOutcome.failed(renderError);
        }
        RenderedChannelContent content = rendered.forKind(kind);
        if (content == null) {
            return DeliveryOutcome.skipped("Template has no enabled " + kind + " delivery method");
        }
        try {
            return sender.send(new ChannelSendRequest(
                    tenantId,
                    notificationType,
                    command.recipients(),
                    content,
                    config,
                    command.options().countUsage()));
        } catch (RuntimeException exception) {
            return DeliveryOutcome.failed(exception.getMessage());
        }
    }
}
