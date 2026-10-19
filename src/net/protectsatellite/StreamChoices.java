// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import org.json.JSONObject;

/** Discovery retains quality names only, never stream URLs/tokens. */
final class StreamChoices {
    static List<String> enabled(JSONObject streams, Settings source) {
        List<String> enabled = new ArrayList<>();
        for (String quality : new String[] {"low", "medium", "high"}) {
            Map<String, Object> values = new HashMap<>(); values.put("console", source.console.toString()); values.put("quality", quality);
            try { ProtectApi.streamUrl(streams, new Settings(values)); enabled.add(quality); }
            catch (Exception ignored) { /* Missing, disabled or invalid stream: do not offer it. */ }
        }
        return Collections.unmodifiableList(enabled);
    }
}
