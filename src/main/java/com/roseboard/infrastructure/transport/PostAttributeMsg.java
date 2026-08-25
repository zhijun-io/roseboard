package com.roseboard.infrastructure.transport;

import java.util.List;

public record PostAttributeMsg(List<KeyValueEntry> kv) {

    public int kvCount() {
        return kv.size();
    }
}
