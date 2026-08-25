package com.roseboard.infrastructure.websocket.cmd.v2.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceTypeWireFilter implements EntityFilter {
    private List<String> deviceTypes;
    private String deviceNameFilter;

    public DeviceTypeWireFilter() {
    }

    public List<String> getDeviceTypes() {
        return deviceTypes;
    }

    public void setDeviceTypes(List<String> deviceTypes) {
        this.deviceTypes = deviceTypes;
    }

    public String getDeviceNameFilter() {
        return deviceNameFilter;
    }

    public void setDeviceNameFilter(String deviceNameFilter) {
        this.deviceNameFilter = deviceNameFilter;
    }
}
