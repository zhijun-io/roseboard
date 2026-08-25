package com.roseboard.infrastructure.transport;

import java.nio.charset.StandardCharsets;

/** Decodes the two transport payload formats without changing the domain message types. */
public final class TransportPayloadDecoder {
    private TransportPayloadDecoder() {
    }

    public static PostTelemetryMsg telemetry(byte[] payload, String contentType) {
        if (isProtobuf(contentType)) {
            try {
                return (PostTelemetryMsg) PostTelemetryProtobufCodec.INSTANCE.parse(payload);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid telemetry protobuf payload", exception);
            }
        }
        return JsonConverter.convertToTelemetryProto(new String(payload, StandardCharsets.UTF_8));
    }

    public static PostAttributeMsg attributes(byte[] payload, String contentType) {
        if (isProtobuf(contentType)) {
            throw new IllegalArgumentException("Attribute protobuf payload is not registered");
        }
        return JsonConverter.convertToAttributesProto(new String(payload, StandardCharsets.UTF_8));
    }

    public static boolean isProtobuf(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("application/x-protobuf");
    }
}
