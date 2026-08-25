package com.roseboard.infrastructure.notification.spi;

public interface SmsClient {
    int sendSms(String numberTo, String message);
    default void destroy() {
    }
}
