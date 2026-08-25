package com.roseboard.infrastructure.transport.mqtt;

public final class MqttTopics {
    public static final class Device {
        public static final String TELEMETRY = "devices/me/telemetry";
        public static final String ATTRIBUTES = "devices/me/attributes";
        public static final String ATTRIBUTES_REQUEST_PREFIX = ATTRIBUTES + "/request/";
        public static final String ATTRIBUTES_RESPONSE_PREFIX = ATTRIBUTES + "/response/";
        public static final String ATTRIBUTES_RESPONSE_FILTER = ATTRIBUTES_RESPONSE_PREFIX + "+";
        public static final String RPC_REQUEST_PREFIX = "devices/me/rpc/request/";
        public static final String RPC_REQUEST_FILTER = RPC_REQUEST_PREFIX + "+";
        public static final String RPC_RESPONSE_PREFIX = "devices/me/rpc/response/";
        public static final String RPC_RESPONSE_FILTER = RPC_RESPONSE_PREFIX + "+";
        public static final String CLAIM = "devices/me/claim";

        private Device() {
        }
    }

    public static final class Provision {
        public static final String CLIENT_ID = "provision";
        public static final String REQUEST = "provision/request";
        public static final String RESPONSE = "provision/response";

        private Provision() {
        }
    }

    public static final class Ota {
        public static final String FIRMWARE_REQUEST_PREFIX = "devices/me/firmware/request/";
        private static final String FIRMWARE_RESPONSE_PREFIX = "devices/me/firmware/response/";
        public static final String FIRMWARE_RESPONSE_FILTER = FIRMWARE_RESPONSE_PREFIX + "+/chunk/+";
        public static final String SOFTWARE_REQUEST_PREFIX = "devices/me/software/request/";
        private static final String SOFTWARE_RESPONSE_PREFIX = "devices/me/software/response/";
        public static final String SOFTWARE_RESPONSE_FILTER = SOFTWARE_RESPONSE_PREFIX + "+/chunk/+";

        public static String firmwareResponseTopic(String requestId, int chunk) {
            return FIRMWARE_RESPONSE_PREFIX + requestId + "/chunk/" + chunk;
        }

        public static String softwareResponseTopic(String requestId, int chunk) {
            return SOFTWARE_RESPONSE_PREFIX + requestId + "/chunk/" + chunk;
        }

        private Ota() {
        }
    }

    private MqttTopics() {
    }
}
