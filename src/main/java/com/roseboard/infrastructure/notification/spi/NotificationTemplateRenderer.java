package com.roseboard.infrastructure.notification.spi;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.RenderedTemplate;
import com.roseboard.infrastructure.notification.model.TemplateDefinition;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.RenderedTemplate;
import com.roseboard.infrastructure.notification.model.TemplateDefinition;

import java.util.Map;

public interface NotificationTemplateRenderer {
    RenderedTemplate render(TemplateDefinition template, Map<String, Object> vars, Iterable<ChannelKind> channels);
}
