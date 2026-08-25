package com.roseboard.infrastructure.notification;

import com.roseboard.infrastructure.notification.channel.EmailNotificationSender;
import com.roseboard.infrastructure.notification.channel.SmtpMailClient;
import com.roseboard.infrastructure.notification.channel.SmsNotificationSender;
import com.roseboard.infrastructure.notification.channel.WebNotificationSender;
import com.roseboard.infrastructure.notification.internal.DefaultNotificationCenter;
import com.roseboard.infrastructure.notification.spi.NotificationChannelConfigResolver;
import com.roseboard.infrastructure.notification.spi.NotificationChannelSender;
import com.roseboard.infrastructure.notification.spi.NotificationInboxWriter;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateRenderer;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateSource;
import com.roseboard.infrastructure.notification.spi.NotificationUsageMeter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Default wiring for the notification starter seam. Host applications provide the DB ports. */
@AutoConfiguration
public class NotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(NotificationCenter.class)
    @ConditionalOnBean({NotificationTemplateSource.class, NotificationTemplateRenderer.class,
            NotificationChannelConfigResolver.class})
    NotificationCenter notificationCenter(NotificationTemplateSource templates,
                                          NotificationTemplateRenderer renderer,
                                          NotificationChannelConfigResolver configs,
                                          ObjectProvider<NotificationChannelSender> senders) {
        return new DefaultNotificationCenter(templates, renderer, configs, senders.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(name = "webNotificationSender")
    @ConditionalOnBean(NotificationInboxWriter.class)
    NotificationChannelSender webNotificationSender(NotificationInboxWriter inboxWriter) {
        return new WebNotificationSender(inboxWriter);
    }

    @Bean
    @ConditionalOnMissingBean(SmtpMailClient.class)
    SmtpMailClient smtpMailClient(@Value("${spring.mail.host:localhost}") String host,
                                  @Value("${spring.mail.port:1025}") int port) {
        return new SmtpMailClient(host, port);
    }

    @Bean
    @ConditionalOnMissingBean(name = "emailNotificationSender")
    @ConditionalOnBean(SmtpMailClient.class)
    NotificationChannelSender emailNotificationSender(
            SmtpMailClient mailClient, ObjectMapper objectMapper,
            ObjectProvider<NotificationUsageMeter> usageMeter,
            @Value("${roseboard.mail.from:noreply@roseboard.local}") String from) {
        return new EmailNotificationSender(mailClient, objectMapper, usageMeter, from);
    }

    @Bean
    @ConditionalOnMissingBean(name = "smsNotificationSender")
    NotificationChannelSender smsNotificationSender(ObjectMapper objectMapper,
                                                     ObjectProvider<NotificationUsageMeter> usageMeter) {
        return new SmsNotificationSender(objectMapper, usageMeter);
    }
}
