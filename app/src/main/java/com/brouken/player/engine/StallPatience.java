package com.brouken.player.engine;

// a barely fast enough host stalls every few seconds, so repeat stops wait longer
public final class StallPatience {

    static final long FIRST_MS = 2_000;
    static final long MOST_MS = 30_000;
    static final long CALM_MS = 180_000;

    private long goalMs;
    private long lastStallAtMs = -1;

    // a new stop has begun at this time: how much to have before playing on
    public long onStall(final long nowMs) {
        if (lastStallAtMs < 0 || nowMs - lastStallAtMs > CALM_MS) {
            goalMs = FIRST_MS;
        } else {
            goalMs = Math.min(MOST_MS, goalMs * 2);
        }
        lastStallAtMs = nowMs;
        return goalMs;
    }
}
