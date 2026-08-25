package com.roseboard.infrastructure.transport;


public interface SessionMsgListener {

    void onMessage(TransportToDevicePayload message);

    default void onGetAttributesResponse(GetAttributeResponseMsg msg) {
    }

    default void onAttributeUpdate(AttributeUpdateNotificationMsg msg) {
    }

    default void onToServerRpcResponse(ToServerRpcResponseMsg msg) {
    }

    default void onSessionClose(SessionCloseNotification notification) {
    }
}
