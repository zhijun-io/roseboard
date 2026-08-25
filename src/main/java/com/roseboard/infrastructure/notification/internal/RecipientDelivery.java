package com.roseboard.infrastructure.notification.internal;

import com.roseboard.infrastructure.notification.model.DeliveryOutcome;
import com.roseboard.infrastructure.notification.model.RecipientRef;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RecipientDelivery {
    private RecipientDelivery() {
    }

    public static DeliveryOutcome deliver(List<RecipientRef> recipients,
                                   Predicate<RecipientRef> eligible,
                                   Consumer<RecipientRef> sendOne,
                                   String emptyMessage) {
        int sent = 0;
        String lastError = null;
        for (RecipientRef recipient : recipients) {
            if (!eligible.test(recipient)) {
                continue;
            }
            try {
                sendOne.accept(recipient);
                sent++;
            } catch (RuntimeException exception) {
                lastError = exception.getMessage();
            }
        }
        if (sent > 0) {
            return DeliveryOutcome.success();
        }
        if (lastError != null) {
            return DeliveryOutcome.failed(lastError);
        }
        return DeliveryOutcome.skipped(emptyMessage);
    }
}
