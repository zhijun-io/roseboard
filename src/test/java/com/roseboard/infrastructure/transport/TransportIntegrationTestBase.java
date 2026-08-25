package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import com.roseboard.support.IntegrationTestBase;

import java.util.concurrent.atomic.AtomicReference;

public abstract class TransportIntegrationTestBase extends IntegrationTestBase {

    protected static SessionMsgListener listener(AtomicReference<TransportToDevicePayload> downlink,
                                                 AtomicReference<SessionCloseNotification> close) {
        return new CapturingListener(downlink, close);
    }

    private record CapturingListener(AtomicReference<TransportToDevicePayload> downlink,
                                     AtomicReference<SessionCloseNotification> close)
            implements SessionMsgListener {
        @Override
        public void onMessage(TransportToDevicePayload message) {
            if (downlink != null) {
                downlink.set(message);
            }
        }

        @Override
        public void onSessionClose(SessionCloseNotification notification) {
            if (close != null) {
                close.set(notification);
            }
        }
    }

    protected static TransportToDevicePayload downlink() {
        return new TransportToDevicePayload("transport.to-device", JacksonUtils.newObjectNode(), null);
    }
}
