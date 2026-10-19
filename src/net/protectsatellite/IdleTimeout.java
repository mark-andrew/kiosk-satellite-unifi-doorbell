// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

/** Monotonic inactivity deadline. Media/state updates never count as interaction. */
final class IdleTimeout {
    private final long duration;
    private long activity;
    private boolean held;
    IdleTimeout(int seconds, long now) { duration = seconds * 1000L; activity = now; }
    void interaction(long now) { activity = now; }
    /** -1 = disabled/suspended, 0 = expire, positive = delay until expiry. */
    long remaining(long now, boolean talking) {
        if (duration == 0) return -1;
        if (talking) { held = true; return -1; }
        if (held) { held = false; activity = now; }
        return Math.max(0, duration - Math.max(0, now - activity));
    }
}
