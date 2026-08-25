package com.roseboard.device.rpc;

/**
 * Aligns with ThingsBoard {@code org.thingsboard.server.common.data.rpc.RpcStatus}.
 */
public enum RpcStatus {
    QUEUED,
    SENT,
    DELIVERED,
    SUCCESSFUL,
    TIMEOUT,
    EXPIRED,
    FAILED,
    DELETED
}
