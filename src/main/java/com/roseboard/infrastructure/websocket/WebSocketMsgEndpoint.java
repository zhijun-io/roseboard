package com.roseboard.infrastructure.websocket;

import org.springframework.web.socket.CloseStatus;

import java.io.IOException;

public interface WebSocketMsgEndpoint {
    void send(WebSocketSessionRef sessionRef, int subscriptionId, String msg) throws IOException;

    void close(WebSocketSessionRef sessionRef, CloseStatus reason) throws IOException;

    boolean isOpen(String externalSessionId);

    void sendPing(WebSocketSessionRef sessionRef, long currentTime) throws IOException;
}
