package com.roseboard.device.telemetry;

import com.roseboard.infrastructure.transport.PostTelemetryMsg;
import com.roseboard.infrastructure.transport.JsonConverter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TelemetryPayloadParser {
    private TelemetryPayloadParser() {
    }

    public static List<TelemetryWrite> parseWrites(JsonNode body) {
        return parseWrites(JsonConverter.convertToTelemetryProto(body));
    }

    public static List<TelemetryWrite> parseWrites(PostTelemetryMsg msg) {
        List<TelemetryWrite> writes = new ArrayList<>();
        for (PostTelemetryMsg.TsKvList tsKv : msg.tsKvList()) {
            Instant timestamp = Instant.ofEpochMilli(tsKv.ts());
            for (com.roseboard.infrastructure.transport.KeyValueEntry entry : tsKv.kv()) {
                writes.add(new TelemetryWrite(entry.key(), entry.toObject(), timestamp, UUID.randomUUID()));
            }
        }
        if (writes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telemetry values are required");
        }
        return writes;
    }
}
