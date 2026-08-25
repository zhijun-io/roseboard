package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.roseboard.infrastructure.websocket.cmd.v2.query.EntityFilter;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EntityFilterQuery {
    private EntityFilter entityFilter;

    public EntityFilter getEntityFilter() {
        return entityFilter;
    }

    public void setEntityFilter(EntityFilter entityFilter) {
        this.entityFilter = entityFilter;
    }
}
