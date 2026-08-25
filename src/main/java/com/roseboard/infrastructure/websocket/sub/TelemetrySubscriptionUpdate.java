package com.roseboard.infrastructure.websocket.sub;

import com.roseboard.infrastructure.websocket.SubscriptionErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class TelemetrySubscriptionUpdate {
    private final int subscriptionId;
    private int errorCode;
    private String errorMsg;
    private Map<String, List<Object[]>> data;

    public TelemetrySubscriptionUpdate(int subscriptionId, Map<String, List<Object[]>> data) {
        this.subscriptionId = subscriptionId;
        this.data = data;
    }

    public TelemetrySubscriptionUpdate(int subscriptionId, SubscriptionErrorCode errorCode, String errorMsg) {
        this.subscriptionId = subscriptionId;
        this.errorCode = errorCode.getCode();
        this.errorMsg = errorMsg != null ? errorMsg : errorCode.getDefaultMsg();
    }

    public int getSubscriptionId() {
        return subscriptionId;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public Map<String, List<Object[]>> getData() {
        return data;
    }

    public static Map<String, List<Object[]>> singlePoint(String key, long ts, Object value) {
        Map<String, List<Object[]>> data = new TreeMap<>();
        List<Object[]> values = new ArrayList<>();
        values.add(new Object[] {ts, String.valueOf(value)});
        data.put(key, values);
        return data;
    }

    public static Map<String, List<Object[]>> fromLatestMap(Map<String, long[]> keyToTsValue) {
        Map<String, List<Object[]>> data = new LinkedHashMap<>();
        keyToTsValue.forEach((key, pair) -> {
            List<Object[]> values = new ArrayList<>();
            values.add(new Object[] {pair[0], String.valueOf(pair[1])});
            data.put(key, values);
        });
        return data;
    }
}
