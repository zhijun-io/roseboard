package com.roseboard.infrastructure.notification.model;

import java.util.EnumMap;
import java.util.Map;

public record RenderedTemplate(
        Map<ChannelKind, RenderedChannelContent> byChannel,
        Map<ChannelKind, String> renderErrors
) {
    public RenderedTemplate(Map<ChannelKind, RenderedChannelContent> byChannel) {
        this(byChannel, Map.of());
    }

    public RenderedTemplate {
        byChannel = byChannel == null ? Map.of() : Map.copyOf(byChannel);
        renderErrors = renderErrors == null ? Map.of() : Map.copyOf(renderErrors);
    }

    public RenderedChannelContent forKind(ChannelKind kind) {
        return byChannel.get(kind);
    }

    public String renderError(ChannelKind kind) {
        return renderErrors.get(kind);
    }

    public static RenderedTemplate empty() {
        return new RenderedTemplate(new EnumMap<>(ChannelKind.class));
    }
}
