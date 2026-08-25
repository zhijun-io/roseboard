package com.roseboard.infrastructure.websocket;

import com.roseboard.infrastructure.websocket.cmd.WsCommandsWrapper;
import com.roseboard.infrastructure.websocket.cmd.v2.CmdUpdate;
import com.roseboard.infrastructure.websocket.sub.TelemetrySubscriptionUpdate;

public interface WebSocketService {
    void handleSessionOpened(WebSocketSessionRef sessionRef);

    void handleSessionClosed(WebSocketSessionRef sessionRef);

    void cleanupIfStale(String sessionId);

    void handleCommands(WebSocketSessionRef sessionRef, WsCommandsWrapper commandsWrapper);

    void sendUpdate(WebSocketSessionRef sessionRef, TelemetrySubscriptionUpdate update);

    void sendUpdateBySessionId(String sessionId, TelemetrySubscriptionUpdate update);

    void sendCmdUpdate(WebSocketSessionRef sessionRef, CmdUpdate update);

    void sendCmdUpdateBySessionId(String sessionId, CmdUpdate update);

    void sendError(WebSocketSessionRef sessionRef, int cmdId, SubscriptionErrorCode errorCode, String errorMsg);
}
