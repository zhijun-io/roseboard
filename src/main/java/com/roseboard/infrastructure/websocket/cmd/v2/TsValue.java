package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TsValue {
    private long ts;
    private String value;
    private Long count;

    public TsValue() {
    }

    public TsValue(long ts, String value) {
        this.ts = ts;
        this.value = value;
    }

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }
}
