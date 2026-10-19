// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;

/** Only these non-connection settings can bypass connection discovery. */
final class SetupPreferences {
    static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "sizing", "viewport", "horizontal", "vertical", "controls", "controlSize", "autoClose",
        "startMuted", "muteWhileTalking", "talkEnabled", "talkLimit", "lockEntity")));
    static Map<String, Object> merge(Map<String, Object> original, Map<String, Object> changes) {
        Map<String, Object> next = new HashMap<>(original);
        for (String key : changes.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Connection changes require validation");
            next.put(key, changes.get(key));
        }
        new Settings(next); return next;
    }
}
