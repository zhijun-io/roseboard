package com.roseboard.notification;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class NotificationHttp {
    private NotificationHttp() {
    }

    public static ChannelKind parseChannelKind(String raw) {
        try {
            return ChannelKind.parse(raw);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
