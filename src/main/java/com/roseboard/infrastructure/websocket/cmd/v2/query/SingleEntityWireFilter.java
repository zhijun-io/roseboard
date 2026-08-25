package com.roseboard.infrastructure.websocket.cmd.v2.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityIdRef;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SingleEntityWireFilter implements EntityFilter {
    private EntityIdRef singleEntity;

    public SingleEntityWireFilter() {
    }

    public EntityIdRef getSingleEntity() {
        return singleEntity;
    }

    public void setSingleEntity(EntityIdRef singleEntity) {
        this.singleEntity = singleEntity;
    }
}
