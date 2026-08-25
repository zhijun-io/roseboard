package com.roseboard.device.attribute;

import com.roseboard.infrastructure.transport.KeyValueEntry;
import com.roseboard.infrastructure.transport.PostAttributeMsg;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

public final class AttributePayloadParser {
    private AttributePayloadParser() {
    }

    public record ClientAttributeWrite(AttributeKey key, AttributeValue value) {
    }

    public static List<ClientAttributeWrite> parseClientAttributes(PostAttributeMsg msg) {
        List<ClientAttributeWrite> writes = new ArrayList<>(msg.kv().size());
        for (KeyValueEntry entry : msg.kv()) {
            writes.add(new ClientAttributeWrite(new AttributeKey(entry.key()), new AttributeValue(entry.toObject())));
        }
        if (writes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute values are required");
        }
        if (writes.size() > DeviceAttributeService.MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many attributes");
        }
        return writes;
    }
}
