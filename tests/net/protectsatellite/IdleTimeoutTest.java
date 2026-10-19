// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;

final class IdleTimeoutTest {
    static void run() throws Exception {
        CoreTest.check(new Settings(Collections.emptyMap()).autoCloseSeconds == 60, "Default inactivity timeout is one minute, including existing installs missing this new field");
        String[] options = {"Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes"}; int[] seconds = {0, 30, 60, 120, 300, 600};
        for (int i = 0; i < options.length; i++) CoreTest.check(new Settings(Collections.singletonMap("autoClose", options[i])).autoCloseSeconds == seconds[i], "Auto-close option " + options[i]);
        CoreTest.rejects(() -> new Settings(Collections.singletonMap("autoClose", "forever-ish")), "Unsupported timeout");
        IdleTimeout timer = new IdleTimeout(60, 1000);
        CoreTest.check(timer.remaining(1000, false) == 60000 && timer.remaining(60999, false) == 1 && timer.remaining(61000, false) == 0, "Timer expires at one minute, not before");
        timer.interaction(61000); CoreTest.check(timer.remaining(61000, false) == 60000 && timer.remaining(120999, false) == 1, "Controls start a fresh full interval");
        CoreTest.check(timer.remaining(121000, true) == -1 && timer.remaining(500000, true) == -1, "Starting/live/stopping Talk suspends expiry even past old deadline");
        CoreTest.check(timer.remaining(500000, false) == 60000 && timer.remaining(560000, false) == 0, "Completed Talk restarts a full inactivity timeout");
        timer.interaction(600000); CoreTest.check(timer.remaining(599000, false) == 60000, "Monotonic clock edge never expires early");
        IdleTimeout off = new IdleTimeout(0, 0); CoreTest.check(off.remaining(Long.MAX_VALUE, false) == -1 && off.remaining(1, true) == -1, "Off never schedules closure");
        Map<String, Object> stored = new HashMap<>(); stored.put("console", "console.example"); stored.put("apiKey", "test-key"); stored.put("cameraId", "cam1");
        try (SetupController setup = new SetupController(stored, selected -> "Off".equals(selected.get("autoClose")), ignored -> {})) {
            setup.preferences(Collections.singletonMap("autoClose", "Off")); CoreTest.check(setup.savePreferences(), "Auto-close editable in native preferences without connection validation");
        }
    }
}
