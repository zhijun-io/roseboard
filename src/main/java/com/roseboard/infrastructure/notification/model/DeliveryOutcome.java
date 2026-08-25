package com.roseboard.infrastructure.notification.model;

public record DeliveryOutcome(DeliveryStatus status, String message) {
    public static DeliveryOutcome success() {
        return new DeliveryOutcome(DeliveryStatus.SUCCESS, null);
    }

    public static DeliveryOutcome failed(String message) {
        return new DeliveryOutcome(DeliveryStatus.FAILED, message);
    }

    public static DeliveryOutcome skipped(String message) {
        return new DeliveryOutcome(DeliveryStatus.SKIPPED, message);
    }

    public static DeliveryOutcome notConfigured(String message) {
        return new DeliveryOutcome(DeliveryStatus.NOT_CONFIGURED, message);
    }

    public static DeliveryOutcome disabled() {
        return new DeliveryOutcome(DeliveryStatus.DISABLED, null);
    }
}
