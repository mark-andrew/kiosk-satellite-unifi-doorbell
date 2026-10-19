// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

/** UI-thread toggle state. A cancelled session must finish before another starts. */
final class TalkToggle {
    enum Phase { IDLE, STARTING, LIVE, STOPPING }
    enum Action { START, STOP, NONE }
    private Phase phase = Phase.IDLE;
    Action tap() {
        if (phase == Phase.IDLE) { phase = Phase.STARTING; return Action.START; }
        if (active()) return Action.STOP; // Caller immediately invokes stop().
        return Action.NONE;
    }
    boolean stop() { boolean wasActive = active(); if (wasActive) phase = Phase.STOPPING; return wasActive; }
    void microphoneActive() { if (phase == Phase.STARTING) phase = Phase.LIVE; }
    void finished() { phase = Phase.IDLE; }
    boolean active() { return phase == Phase.STARTING || phase == Phase.LIVE; }
    boolean stopping() { return phase == Phase.STOPPING; }
    boolean idle() { return phase == Phase.IDLE; }
    String label() {
        switch (phase) {
            case STARTING: return "Starting — tap to stop";
            case LIVE: return "Microphone live — tap to stop";
            case STOPPING: return "Stopping microphone…";
            default: return "Talk";
        }
    }
}
