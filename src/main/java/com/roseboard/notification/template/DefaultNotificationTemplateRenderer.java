package com.roseboard.notification.template;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.RenderedChannelContent;
import com.roseboard.infrastructure.notification.model.RenderedTemplate;
import com.roseboard.infrastructure.notification.model.TemplateDefinition;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateRenderer;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.EnumMap;
import java.util.Map;

@Component
public class DefaultNotificationTemplateRenderer implements NotificationTemplateRenderer {
    private final Configuration freemarker;

    public DefaultNotificationTemplateRenderer() {
        freemarker = new Configuration(Configuration.VERSION_2_3_32);
        freemarker.setDefaultEncoding("UTF-8");
    }

    @Override
    public RenderedTemplate render(TemplateDefinition template, Map<String, Object> vars,
                                   Iterable<ChannelKind> channels) {
        Map<ChannelKind, RenderedChannelContent> rendered = new EnumMap<>(ChannelKind.class);
        Map<ChannelKind, String> renderErrors = new EnumMap<>(ChannelKind.class);
        for (ChannelKind kind : channels) {
            renderChannel(template, vars, kind, rendered, renderErrors);
        }
        return new RenderedTemplate(rendered, renderErrors);
    }

    private void renderChannel(TemplateDefinition template, Map<String, Object> vars, ChannelKind kind,
                               Map<ChannelKind, RenderedChannelContent> rendered,
                               Map<ChannelKind, String> renderErrors) {
        JsonNode method = template.deliveryMethods().path(kind.name());
        if (!method.path("enabled").asBoolean(false)) {
            return;
        }
        try {
            String engine = resolveEngine(method, template);
            String subject = renderText(engine, method.path("subject").asString(""), vars);
            String body = renderText(engine, method.path("body").asString(""), vars);
            boolean html = kind == ChannelKind.EMAIL && method.path("html").asBoolean(true);
            rendered.put(kind, new RenderedChannelContent(subject, body, html));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            renderErrors.put(kind, message == null || message.isBlank()
                    ? "Template render failed" : message);
        }
    }

    private static String resolveEngine(JsonNode method, TemplateDefinition template) {
        String engine = method.path("renderEngine").asString(null);
        if (engine == null || engine.isBlank()) {
            engine = template.renderEngine();
        }
        return engine == null || engine.isBlank() ? "SIMPLE" : engine;
    }

    private String renderText(String engine, String text, Map<String, Object> vars) {
        if (text == null) {
            return "";
        }
        if ("FREEMARKER".equalsIgnoreCase(engine)) {
            return renderFreemarker(text, vars);
        }
        return renderSimple(text, vars);
    }

    private String renderSimple(String text, Map<String, Object> vars) {
        String result = text;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private String renderFreemarker(String text, Map<String, Object> vars) {
        try {
            Template template = new Template("inline", new StringReader(text), freemarker);
            StringWriter output = new StringWriter();
            template.process(vars, output);
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Template render failed", exception);
        }
    }
}
