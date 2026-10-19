// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

final class TalkToggleTest {
    static void run() {
        TalkToggle toggle = new TalkToggle();
        CoreTest.check(toggle.idle() && !toggle.active() && toggle.label().equals("Talk"), "Toggle starts with microphone off");
        CoreTest.check(toggle.tap() == TalkToggle.Action.START && toggle.active(), "First completed click starts microphone session");
        CoreTest.check(toggle.label().contains("Starting") && !toggle.label().contains("live"), "Starting session is not falsely labelled live");
        toggle.microphoneActive();
        CoreTest.check(toggle.label().equals("Microphone live — tap to stop") && toggle.active(), "Persistent live button label after microphone starts");
        CoreTest.check(toggle.tap() == TalkToggle.Action.STOP && toggle.stop(), "Second click stops live session");
        CoreTest.check(toggle.stopping() && !toggle.active(), "Stop immediately clears transmit intent");
        CoreTest.check(toggle.tap() == TalkToggle.Action.NONE, "Cannot restart while old worker still owns microphone");
        toggle.microphoneActive();
        CoreTest.check(toggle.stopping() && !toggle.active(), "Late microphone-active callback cannot resurrect a stopped session");
        CoreTest.check(!toggle.stop(), "Repeated close/stop is harmless");
        toggle.finished();
        CoreTest.check(toggle.idle() && toggle.label().equals("Talk"), "Worker cleanup restores idle label");
        CoreTest.check(toggle.tap() == TalkToggle.Action.START, "Toggle may restart only after cleanup");
        CoreTest.check(toggle.tap() == TalkToggle.Action.STOP && toggle.stop(), "Second click cancels pending connection before microphone starts");
        toggle.finished();
        for (String reason : new String[] {"timeout", "Back", "background", "stream failure", "surface destroyed", "plugin stop"}) {
            CoreTest.check(toggle.tap() == TalkToggle.Action.START, "Start before lifecycle stop: " + reason);
            toggle.microphoneActive();
            CoreTest.check(toggle.stop() && !toggle.active(), "Lifecycle event cancels toggle: " + reason);
            toggle.finished();
        }
        CoreTest.check(!toggle.stop() && toggle.idle(), "Stop while idle does not create a session");
    }
}
