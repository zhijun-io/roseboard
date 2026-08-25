package com.roseboard.infrastructure.transport.http;

import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.transport.GetAttributeRequestMsg;
import com.roseboard.infrastructure.transport.SubscribeToAttributeUpdatesMsg;
import com.roseboard.infrastructure.transport.ToServerRpcRequestMsg;
import com.roseboard.infrastructure.transport.AttributeUpdateNotificationMsg;
import com.roseboard.infrastructure.transport.ToServerRpcResponseMsg;
import com.roseboard.infrastructure.transport.GetAttributeResponseMsg;
import com.roseboard.infrastructure.transport.SessionCloseNotification;
import com.roseboard.infrastructure.transport.SessionInfo;
import com.roseboard.infrastructure.transport.SessionMsgListener;
import com.roseboard.infrastructure.transport.TransportRpcService;
import com.roseboard.infrastructure.transport.TransportDeviceApiService;
import com.roseboard.infrastructure.transport.TransportService;
import com.roseboard.infrastructure.transport.TransportServiceCallback;
import com.roseboard.infrastructure.transport.TransportToDevicePayload;
import com.roseboard.infrastructure.transport.ValidateDeviceCredentialsResponse;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import com.roseboard.infrastructure.transport.JsonConverter;
import com.roseboard.ota.OtaPackageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import com.roseboard.infrastructure.transport.TransportPayloadDecoder;
import org.springframework.web.context.request.async.DeferredResult;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 设备 HTTP 协议接口：处理设备侧属性、遥测、RPC、Claim 和 OTA 请求。
 */
@RestController
@RequestMapping("/api/http")
public class DeviceApiController {
    private static final Logger log = LoggerFactory.getLogger(DeviceApiController.class);

    @Autowired
    private TransportService transportService;

    @Autowired
    private TransportRpcService transportRpcService;

    @Autowired
    private TransportDeviceApiService transportDeviceApiService;

    @Autowired
    private TransportProperties transportProperties;

    @Value("${transport.http.request_timeout:10000}")
    private long defaultTimeout;

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/attributes", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ResponseEntity> getDeviceAttributes(
            @PathVariable("deviceToken") String deviceToken,
            @RequestParam(value = "clientKeys", required = false, defaultValue = "") String clientKeys,
            @RequestParam(value = "sharedKeys", required = false, defaultValue = "") String sharedKeys) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> {
            List<String> clientKeySet = StringUtils.hasText(clientKeys) ? Arrays.asList(clientKeys.split(",")) : null;
            List<String> sharedKeySet = StringUtils.hasText(sharedKeys) ? Arrays.asList(sharedKeys.split(",")) : null;
            GetAttributeRequestMsg request = new GetAttributeRequestMsg(0, clientKeySet, sharedKeySet);
            transportService.registerSyncSession(
                    sessionInfo,
                    new HttpSyncSessionListener(responseWriter),
                    defaultTimeout);
            transportService.process(
                    sessionInfo,
                    request,
                    new SessionCloseOnErrorCallback(transportService, sessionInfo));
        });
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/attributes", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postDeviceAttributes(
            @PathVariable("deviceToken") String deviceToken,
            @RequestBody byte[] payload,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> transportService.process(
                sessionInfo,
                TransportPayloadDecoder.attributes(payload, contentType),
                new HttpOkCallback(responseWriter)));
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/telemetry", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postTelemetry(
            @PathVariable("deviceToken") String deviceToken,
            @RequestBody byte[] payload,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> transportService.process(
                sessionInfo,
                TransportPayloadDecoder.telemetry(payload, contentType),
                new HttpOkCallback(responseWriter)));
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/attributes/updates", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ResponseEntity> subscribeToAttributeUpdates(
            @PathVariable("deviceToken") String deviceToken,
            @RequestParam(value = "timeout", required = false, defaultValue = "0") long timeout) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> {
            long waitMs = timeout > 0 ? timeout : defaultTimeout;
            transportService.registerSyncSession(
                    sessionInfo,
                    new HttpSyncSessionListener(responseWriter),
                    waitMs);
            transportService.process(
                    sessionInfo,
                    SubscribeToAttributeUpdatesMsg.subscribe(),
                    new SessionCloseOnErrorCallback(transportService, sessionInfo));
        });
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/rpc", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ResponseEntity> postDeviceRpc(
            @PathVariable("deviceToken") String deviceToken,
            @RequestBody String json) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> {
            JsonNode body = JacksonUtils.toJsonNode(json);
            if (body == null || body.isNull() || !body.hasNonNull("method") || !body.has("params")) {
                responseWriter.setResult(ResponseEntity.badRequest().build());
                return;
            }
            transportService.registerSyncSession(
                    sessionInfo,
                    new HttpSyncSessionListener(responseWriter),
                    defaultTimeout);
            transportService.process(
                    sessionInfo,
                    new ToServerRpcRequestMsg(0, body.get("method").asText(), body.get("params")),
                    new SessionCloseOnErrorCallback(transportService, sessionInfo));
        });
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/rpc", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ResponseEntity> subscribeToCommands(
            @PathVariable("deviceToken") String deviceToken,
            @RequestParam(value = "timeout", required = false, defaultValue = "0") long timeout) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) ->
                transportRpcService.subscribeToRpc(sessionInfo, responseWriter, timeout));
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/rpc/{requestId}", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> replyToCommand(
            @PathVariable("deviceToken") String deviceToken,
            @PathVariable("requestId") Integer requestId,
            @RequestBody String json) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) ->
                transportRpcService.replyToRpc(sessionInfo, requestId, json, responseWriter));
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/claim", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> saveClaimingInfo(
            @PathVariable("deviceToken") String deviceToken,
            @RequestBody(required = false) String json) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> {
            try {
                transportDeviceApiService.registerClaimingInfo(
                        sessionInfo.tenantId(), sessionInfo.deviceId(), json);
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.OK));
            } catch (RuntimeException exception) {
                log.debug("Failed to process claim request", exception);
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
            }
        });
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/provision", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ResponseEntity> provisionDevice(@RequestBody String json) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        try {
            responseWriter.setResult(transportDeviceApiService.provision(json));
        } catch (RuntimeException exception) {
            log.debug("Failed to process provision request", exception);
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        }
        return responseWriter;
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/firmware", method = RequestMethod.GET)
    public DeferredResult<ResponseEntity> getFirmware(
            @PathVariable("deviceToken") String deviceToken,
            @RequestParam("title") String title,
            @RequestParam("version") String version,
            @RequestParam(value = "size", required = false, defaultValue = "0") int size,
            @RequestParam(value = "chunk", required = false, defaultValue = "0") int chunk) {
        return downloadOta(deviceToken, OtaPackageType.FIRMWARE, title, version, size, chunk);
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/{deviceToken}/software", method = RequestMethod.GET)
    public DeferredResult<ResponseEntity> getSoftware(
            @PathVariable("deviceToken") String deviceToken,
            @RequestParam("title") String title,
            @RequestParam("version") String version,
            @RequestParam(value = "size", required = false, defaultValue = "0") int size,
            @RequestParam(value = "chunk", required = false, defaultValue = "0") int chunk) {
        return downloadOta(deviceToken, OtaPackageType.SOFTWARE, title, version, size, chunk);
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/telemetry", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postTelemetryWithHeader(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Authorization", required = false) String xAuthorization,
            @RequestHeader(value = "X-Device-Access-Token", required = false) String deviceAccessToken,
            @RequestHeader(value = "X-Device-Credentials-Id", required = false) String credentialsId,
            @RequestHeader(value = "X-Device-Credentials-Value", required = false) String credentialsValue) {
        return withHeaderAuth(authorization, xAuthorization, deviceAccessToken, credentialsId, credentialsValue,
                (responseWriter, sessionInfo) -> transportService.process(
                        sessionInfo, TransportPayloadDecoder.telemetry(payload, contentType),
                        new HttpOkCallback(responseWriter)));
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @RequestMapping(value = "/attributes", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postAttributesWithHeader(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Authorization", required = false) String xAuthorization,
            @RequestHeader(value = "X-Device-Access-Token", required = false) String deviceAccessToken,
            @RequestHeader(value = "X-Device-Credentials-Id", required = false) String credentialsId,
            @RequestHeader(value = "X-Device-Credentials-Value", required = false) String credentialsValue) {
        return withHeaderAuth(authorization, xAuthorization, deviceAccessToken, credentialsId, credentialsValue,
                (responseWriter, sessionInfo) -> transportService.process(
                        sessionInfo, TransportPayloadDecoder.attributes(payload, contentType),
                        new HttpOkCallback(responseWriter)));
    }

    private DeferredResult<ResponseEntity> withHeaderAuth(String authorization,
                                                           String xAuthorization,
                                                           String deviceAccessToken,
                                                           String credentialsId,
                                                           String credentialsValue,
                                                           BiConsumer<DeferredResult<ResponseEntity>, SessionInfo> action) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportService.validateHttpCredentials(
                authorization, xAuthorization, deviceAccessToken, credentialsId, credentialsValue,
                new DeviceAuthCallback(responseWriter, transportProperties.getCluster().resolvedNodeId(),
                        sessionInfo -> action.accept(responseWriter, sessionInfo)));
        return responseWriter;
    }

    private DeferredResult<ResponseEntity> downloadOta(String deviceToken, OtaPackageType type,
                                                       String title, String version, int size, int chunk) {
        return withAuth(deviceToken, (responseWriter, sessionInfo) -> responseWriter.setResult(
                transportDeviceApiService.downloadOta(
                        sessionInfo.tenantId(),
                        sessionInfo.deviceId(),
                        sessionInfo.deviceProfileId(),
                        type,
                        title,
                        version,
                        size,
                        chunk)));
    }

    private DeferredResult<ResponseEntity> withAuth(String deviceToken,
                                                      BiConsumer<DeferredResult<ResponseEntity>, SessionInfo> action) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportService.validateDeviceToken(
                deviceToken,
                new DeviceAuthCallback(responseWriter, transportProperties.getCluster().resolvedNodeId(), sessionInfo -> action.accept(responseWriter, sessionInfo)));
        return responseWriter;
    }

    static final class DeviceAuthCallback implements TransportServiceCallback<ValidateDeviceCredentialsResponse> {
        private final DeferredResult<ResponseEntity> responseWriter;
        private final String nodeId;
        private final Consumer<SessionInfo> onSuccess;

        DeviceAuthCallback(DeferredResult<ResponseEntity> responseWriter,
                           String nodeId,
                           Consumer<SessionInfo> onSuccess) {
            this.responseWriter = responseWriter;
            this.nodeId = nodeId;
            this.onSuccess = onSuccess;
        }

        @Override
        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
            if (msg.hasDeviceInfo()) {
                onSuccess.accept(SessionInfo.create(msg, nodeId, UUID.randomUUID(), System.currentTimeMillis()));
            } else {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
            }
        }

        @Override
        public void onError(Throwable e) {
            String body = null;
            if (e instanceof HttpMessageNotReadableException || e instanceof JacksonException) {
                body = e.getMessage();
                log.debug("Failed to process request in DeviceAuthCallback: {}", body);
            } else {
                log.warn("Failed to process request in DeviceAuthCallback", e);
            }
            responseWriter.setResult(new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    static final class HttpOkCallback implements TransportServiceCallback<Void> {
        private final DeferredResult<ResponseEntity> responseWriter;

        HttpOkCallback(DeferredResult<ResponseEntity> responseWriter) {
            this.responseWriter = responseWriter;
        }

        @Override
        public void onSuccess(Void msg) {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.OK));
        }

        @Override
        public void onError(Throwable e) {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    static final class SessionCloseOnErrorCallback implements TransportServiceCallback<Void> {
        private final TransportService transportService;
        private final SessionInfo sessionInfo;

        SessionCloseOnErrorCallback(TransportService transportService, SessionInfo sessionInfo) {
            this.transportService = transportService;
            this.sessionInfo = sessionInfo;
        }

        @Override
        public void onSuccess(Void msg) {
        }

        @Override
        public void onError(Throwable e) {
            transportService.deregisterSession(sessionInfo);
        }
    }

    static final class HttpSyncSessionListener implements SessionMsgListener {
        private final DeferredResult<ResponseEntity> responseWriter;

        HttpSyncSessionListener(DeferredResult<ResponseEntity> responseWriter) {
            this.responseWriter = responseWriter;
        }

        @Override
        public void onMessage(TransportToDevicePayload message) {
        }

        @Override
        public void onGetAttributesResponse(GetAttributeResponseMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg), HttpStatus.OK));
        }

        @Override
        public void onAttributeUpdate(AttributeUpdateNotificationMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg), HttpStatus.OK));
        }

        @Override
        public void onToServerRpcResponse(ToServerRpcResponseMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg), HttpStatus.OK));
        }

        @Override
        public void onSessionClose(SessionCloseNotification notification) {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.REQUEST_TIMEOUT));
        }
    }
}
