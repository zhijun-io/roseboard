package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.rpc.DeviceRpcEntity;
import com.roseboard.device.rpc.DeviceRpcService;
import com.roseboard.device.rpc.RpcStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class TransportRpcService {
    private static final long MIN_TIMEOUT_MS = 5_000L;
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private final TransportSessionRegistry sessions;
    private final DeviceRpcService deviceRpcService;
    private final long defaultDeviceTimeoutMs;

    private final ConcurrentHashMap<DeviceRequestKey, CompletableFuture<JsonNode>> pendingTwoway =
            new ConcurrentHashMap<>();

    public TransportRpcService(TransportSessionRegistry sessions,
                               DeviceRpcService deviceRpcService,
                               @Value("${transport.http.request_timeout:10000}") long defaultDeviceTimeoutMs) {
        this.sessions = sessions;
        this.deviceRpcService = deviceRpcService;
        this.defaultDeviceTimeoutMs = defaultDeviceTimeoutMs;
    }

    public void processAdminRpc(UUID tenantId,
                                UUID deviceId,
                                JsonNode body,
                                boolean oneWay,
                                DeferredResult<ResponseEntity> result) {
        if (body == null || body.isNull() || !body.hasNonNull("method") || !body.has("params")) {
            result.setResult(ResponseEntity.badRequest().build());
            return;
        }
        boolean persistent = body.path("persistent").asBoolean(false);
        if (persistent) {
            long timeout = body.path("timeout").asLong(DEFAULT_TIMEOUT_MS);
            long expirationTime = body.has("expirationTime")
                    ? body.get("expirationTime").asLong()
                    : System.currentTimeMillis() + Math.max(MIN_TIMEOUT_MS, timeout);
            JsonNode additionalInfo = body.get("additionalInfo");
            DeviceRpcEntity created = deviceRpcService.create(
                    tenantId, deviceId, body, expirationTime, additionalInfo);
            result.setResult(ResponseEntity.ok(created.getId().toString()));
            return;
        }

        Optional<UUID> sessionId = sessions.activeSessionId(deviceId);
        if (sessionId.isEmpty()) {
            result.setResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build());
            return;
        }

        int requestId = sessions.nextRequestId(sessionId.get());
        TransportToDevicePayload payload = toPayload(body, requestId);

        if (oneWay) {
            if (sessions.deliver(deviceId, payload) != DeliverResult.DELIVERED) {
                result.setResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build());
            } else {
                result.setResult(ResponseEntity.ok().build());
            }
            return;
        }

        DeviceRequestKey key = new DeviceRequestKey(deviceId, requestId);
        CompletableFuture<JsonNode> replyFuture = new CompletableFuture<>();
        pendingTwoway.put(key, replyFuture);
        if (sessions.deliver(deviceId, payload) != DeliverResult.DELIVERED) {
            pendingTwoway.remove(key);
            result.setResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build());
            return;
        }

        long timeoutMs = Math.max(MIN_TIMEOUT_MS, body.path("timeout").asLong(DEFAULT_TIMEOUT_MS));
        replyFuture.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
            pendingTwoway.remove(key);
            if (error != null || response == null) {
                result.setResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build());
            } else {
                result.setResult(ResponseEntity.ok(response));
            }
        });
    }

    public void subscribeToRpc(SessionInfo sessionInfo, DeferredResult<ResponseEntity> writer, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : defaultDeviceTimeoutMs;
        SessionMsgListener listener = new RpcWaitListener(writer);
        sessions.register(sessionInfo, listener, Duration.ofMillis(timeout));

        deviceRpcService.findOldestQueued(sessionInfo.tenantId(), sessionInfo.deviceId())
                .ifPresent(rpc -> {
                    int requestId = sessions.nextRequestId(sessionInfo.sessionId());
                    deviceRpcService.claimQueued(sessionInfo.tenantId(), rpc.getId(), requestId);
                    sessions.deliver(sessionInfo.deviceId(), toPayload(rpc.getRequest(), requestId));
                });
    }

    public void replyToRpc(SessionInfo sessionInfo, int requestId, String jsonBody,
                           DeferredResult<ResponseEntity> writer) {
        completeDeviceRpcReply(sessionInfo, requestId, jsonBody);
        writer.setResult(new ResponseEntity<>(HttpStatus.OK));
    }

    public void activateRpcSubscription(SessionInfo sessionInfo) {
        sessions.setSubscribedToRpc(sessionInfo.sessionId(), true);
        deliverOldestQueuedRpc(sessionInfo);
    }

    public void completeDeviceRpcReply(SessionInfo sessionInfo, int requestId, String jsonBody) {
        JsonNode response = JacksonUtils.toJsonNode(jsonBody);
        CompletableFuture<JsonNode> pending = pendingTwoway.remove(
                new DeviceRequestKey(sessionInfo.deviceId(), requestId));
        if (pending != null) {
            pending.complete(response);
        }
        deviceRpcService.completeSentReply(
                sessionInfo.tenantId(), sessionInfo.deviceId(), requestId, response);
    }

    private void deliverOldestQueuedRpc(SessionInfo sessionInfo) {
        deviceRpcService.findOldestQueued(sessionInfo.tenantId(), sessionInfo.deviceId())
                .ifPresent(rpc -> {
                    int requestId = sessions.nextRequestId(sessionInfo.sessionId());
                    deviceRpcService.claimQueued(sessionInfo.tenantId(), rpc.getId(), requestId);
                    sessions.deliver(sessionInfo.deviceId(), toPayload(rpc.getRequest(), requestId));
                });
    }

    private static TransportToDevicePayload toPayload(JsonNode request, int requestId) {
        return new TransportToDevicePayload(
                request.get("method").asText(),
                request.get("params"),
                requestId);
    }

    private static final class RpcWaitListener implements SessionMsgListener {
        private final DeferredResult<ResponseEntity> writer;

        private RpcWaitListener(DeferredResult<ResponseEntity> writer) {
            this.writer = writer;
        }

        @Override
        public void onMessage(TransportToDevicePayload message) {
            writer.setResult(new ResponseEntity<>(JsonConverter.toDeviceRpcJson(message), HttpStatus.OK));
        }

        @Override
        public void onSessionClose(SessionCloseNotification notification) {
            writer.setResult(new ResponseEntity<>(HttpStatus.REQUEST_TIMEOUT));
        }
    }

    private record DeviceRequestKey(UUID deviceId, int requestId) {
    }
}
