package com.roseboard.infrastructure.websocket.cmd.v2.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SingleEntityWireFilter.class, name = "singleEntity"),
        @JsonSubTypes.Type(value = DeviceTypeWireFilter.class, name = "deviceType")
})
public interface EntityFilter {
}
