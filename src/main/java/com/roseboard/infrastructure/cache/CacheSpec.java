package com.roseboard.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;

public final class CacheSpec {
    private final String name;
    private boolean enabled;
    private Duration timeToLive;
    private long maxSize;

    public CacheSpec(String name, boolean enabled, Duration timeToLive, long maxSize) {
        this.name = Objects.requireNonNull(name, "name");
        this.enabled = enabled;
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        this.maxSize = maxSize;
    }

    public String name() {
        return name;
    }

    public boolean enabled() {
        return enabled && maxSize > 0 && !timeToLive.isZero() && !timeToLive.isNegative();
    }


    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration timeToLive() {
        return timeToLive;
    }

    public void setTimeToLive(Duration timeToLive) {
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
    }

    public long maxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CacheSpec that)) {
            return false;
        }
        return enabled == that.enabled
                && maxSize == that.maxSize
                && name.equals(that.name)
                && timeToLive.equals(that.timeToLive);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, enabled, timeToLive, maxSize);
    }

    @Override
    public String toString() {
        return "CacheSpec[name=%s, enabled=%s, timeToLive=%s, maxSize=%d]"
                .formatted(name, enabled, timeToLive, maxSize);
    }
}
