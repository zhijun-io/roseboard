package com.roseboard.infrastructure.transport;

import java.util.List;

public record PostTelemetryMsg(List<TsKvList> tsKvList) {

    public record TsKvList(long ts, List<KeyValueEntry> kv) {
    }

    public int dataPointCount() {
        int count = 0;
        for (TsKvList tsKv : tsKvList) {
            count += tsKv.kv().size();
        }
        return count;
    }
}
