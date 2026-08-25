package com.roseboard.infrastructure.websocket.cmd.plugin;

import com.roseboard.infrastructure.websocket.cmd.WsCommandsWrapper;
import com.roseboard.infrastructure.websocket.cmd.v1.AttributesSubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.GetHistoryCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.TimeseriesSubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataUnsubscribeCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountUnsubscribeCmd;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TB legacy telemetry plugin envelope ({@code /api/ws/plugins/telemetry}).
 */
public class TelemetryCmdsWrapper {
    private List<AttributesSubscriptionCmd> attrSubCmds;
    private List<TimeseriesSubscriptionCmd> tsSubCmds;
    private List<GetHistoryCmd> historyCmds;
    private List<EntityDataCmd> entityDataCmds;
    private List<EntityDataUnsubscribeCmd> entityDataUnsubscribeCmds;
    private List<EntityCountCmd> entityCountCmds;
    private List<EntityCountUnsubscribeCmd> entityCountUnsubscribeCmds;

    public List<AttributesSubscriptionCmd> getAttrSubCmds() {
        return attrSubCmds;
    }

    public void setAttrSubCmds(List<AttributesSubscriptionCmd> attrSubCmds) {
        this.attrSubCmds = attrSubCmds;
    }

    public List<TimeseriesSubscriptionCmd> getTsSubCmds() {
        return tsSubCmds;
    }

    public void setTsSubCmds(List<TimeseriesSubscriptionCmd> tsSubCmds) {
        this.tsSubCmds = tsSubCmds;
    }

    public List<GetHistoryCmd> getHistoryCmds() {
        return historyCmds;
    }

    public void setHistoryCmds(List<GetHistoryCmd> historyCmds) {
        this.historyCmds = historyCmds;
    }

    public List<EntityDataCmd> getEntityDataCmds() {
        return entityDataCmds;
    }

    public void setEntityDataCmds(List<EntityDataCmd> entityDataCmds) {
        this.entityDataCmds = entityDataCmds;
    }

    public List<EntityDataUnsubscribeCmd> getEntityDataUnsubscribeCmds() {
        return entityDataUnsubscribeCmds;
    }

    public void setEntityDataUnsubscribeCmds(List<EntityDataUnsubscribeCmd> entityDataUnsubscribeCmds) {
        this.entityDataUnsubscribeCmds = entityDataUnsubscribeCmds;
    }

    public List<EntityCountCmd> getEntityCountCmds() {
        return entityCountCmds;
    }

    public void setEntityCountCmds(List<EntityCountCmd> entityCountCmds) {
        this.entityCountCmds = entityCountCmds;
    }

    public List<EntityCountUnsubscribeCmd> getEntityCountUnsubscribeCmds() {
        return entityCountUnsubscribeCmds;
    }

    public void setEntityCountUnsubscribeCmds(List<EntityCountUnsubscribeCmd> entityCountUnsubscribeCmds) {
        this.entityCountUnsubscribeCmds = entityCountUnsubscribeCmds;
    }

    @JsonIgnore
    public WsCommandsWrapper toCommonCmdsWrapper() {
        return new WsCommandsWrapper(null, Stream.of(
                        attrSubCmds, tsSubCmds, historyCmds, entityDataCmds, entityDataUnsubscribeCmds,
                        entityCountCmds, entityCountUnsubscribeCmds)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));
    }
}
