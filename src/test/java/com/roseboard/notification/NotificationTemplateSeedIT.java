package com.roseboard.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.*;
import com.roseboard.infrastructure.notification.model.*;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateRenderer;
import com.roseboard.notification.template.NotificationTemplateEntity;
import com.roseboard.notification.template.NotificationTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class NotificationTemplateSeedIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Autowired NotificationTemplateMapper templateMapper;
    @Autowired
    NotificationTemplateRenderer templateRenderer;

    @Test
    void systemTemplatesAreSeeded() {
        NotificationTemplateEntity activation = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .isNull(NotificationTemplateEntity::getTenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, "auth.activation"));
        assertNotNull(activation);
        assertEqualsRenderEngine(activation, "FREEMARKER");

        NotificationTemplateEntity usage = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .isNull(NotificationTemplateEntity::getTenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, "usage.threshold"));
        assertNotNull(usage);
        assertTrue(usage.getDeliveryMethods().path("WEB").path("enabled").asBoolean(false));
        assertTrue(usage.getDeliveryMethods().path("EMAIL").path("enabled").asBoolean(false));
    }

    @Test
    void authActivationRendersHtmlLink() {
        NotificationTemplateEntity activation = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .isNull(NotificationTemplateEntity::getTenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, "auth.activation"));
        TemplateDefinition definition = new TemplateDefinition(
                activation.getId(), activation.getTenantId(), activation.getTemplateKey(),
                activation.getRenderEngine(), activation.getNotificationType(), activation.getDeliveryMethods());
        RenderedTemplate rendered = templateRenderer.render(definition,
                java.util.Map.of("link", "https://app.test/login/createPassword?activateToken=abc"),
                java.util.Set.of(ChannelKind.EMAIL));
        RenderedChannelContent email = rendered.forKind(ChannelKind.EMAIL);
        assertNotNull(email);
        assertTrue(email.html());
        assertTrue(email.body().contains("https://app.test/login/createPassword?activateToken=abc"));
        assertFalse(email.body().contains("${link}"));
    }

    private static void assertEqualsRenderEngine(NotificationTemplateEntity entity, String engine) {
        assertTrue(engine.equalsIgnoreCase(entity.getRenderEngine()));
    }
}
