package com.roseboard.device.query.filter;

import java.util.Collections;
import java.util.List;

public class DeviceTypeFilter {
    private List<String> deviceTypes;
    private String deviceNameFilter;

    public DeviceTypeFilter() {
    }

    public DeviceTypeFilter(List<String> deviceTypes, String deviceNameFilter) {
        this.deviceTypes = deviceTypes;
        this.deviceNameFilter = deviceNameFilter;
    }

    public List<String> getDeviceTypes() {
        if (deviceTypes == null || deviceTypes.isEmpty()) {
            return Collections.emptyList();
        }
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

    public EntityFilterType getType() {
        return EntityFilterType.DEVICE_TYPE;
    }
}
