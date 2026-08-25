package com.roseboard.infrastructure.queue;

import java.util.HashMap;
import java.util.Map;

public class DefaultQueueMessage implements QueueMessage {
    private final String key;
    private final byte[] data;
    private final Map<String, byte[]> headers;

    public DefaultQueueMessage(String key, byte[] data, Map<String, byte[]> headers) {
        this.key = key;
        this.data = data;
        this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
    }

    public DefaultQueueMessage(QueueMessage msg) {
        this(msg.getKey(), msg.getData(), msg.getHeaders());
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public Map<String, byte[]> getHeaders() {
        return headers;
    }

    @Override
    public byte[] getData() {
        return data;
    }
}
