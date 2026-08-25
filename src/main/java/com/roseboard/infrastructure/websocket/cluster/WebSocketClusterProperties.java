package com.roseboard.infrastructure.websocket.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "server.ws.cluster")
public class WebSocketClusterProperties {
    /**
     * When true, domain push events are broadcast via Redis; each node delivers to local sessions only.
     */
    private boolean enabled;
    /**
     * Redis pub/sub channel for ws.push (Plan Slice 7).
     */
    private String pushChannel = "roseboard:ws:push";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPushChannel() {
        return pushChannel;
    }

    public void setPushChannel(String pushChannel) {
        this.pushChannel = pushChannel;
    }
}
