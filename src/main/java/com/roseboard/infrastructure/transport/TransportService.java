package com.roseboard.infrastructure.transport;

public interface TransportService {

    void validateDeviceToken(String deviceToken,
                             TransportServiceCallback<ValidateDeviceCredentialsResponse> callback);

    void validateHttpCredentials(String authorization,
                                 String xAuthorization,
                                 String deviceAccessToken,
                                 String credentialsId,
                                 String credentialsValue,
                                 TransportServiceCallback<ValidateDeviceCredentialsResponse> callback);
    void process(SessionInfo sessionInfo, PostTelemetryMsg msg, TransportServiceCallback<Void> callback);

    void process(SessionInfo sessionInfo, PostAttributeMsg msg, TransportServiceCallback<Void> callback);

    void process(SessionInfo sessionInfo, GetAttributeRequestMsg msg, TransportServiceCallback<Void> callback);

    void process(SessionInfo sessionInfo, SubscribeToAttributeUpdatesMsg msg, TransportServiceCallback<Void> callback);

    void process(SessionInfo sessionInfo, ToServerRpcRequestMsg msg, TransportServiceCallback<Void> callback);

    void registerSyncSession(SessionInfo sessionInfo, SessionMsgListener listener, long timeout);

    void registerAsyncSession(SessionInfo sessionInfo, SessionMsgListener listener, long idleTimeoutMs);

    void deregisterSession(SessionInfo sessionInfo);

    default void registerAsyncSession(SessionInfo sessionInfo, SessionMsgListener listener,
                                      long idleTimeoutMs, String protocol) {
        registerAsyncSession(sessionInfo, listener, idleTimeoutMs);
    }
    void recordActivity(SessionInfo sessionInfo);
}
