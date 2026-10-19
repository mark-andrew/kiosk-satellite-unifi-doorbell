// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

/** SDK 1 partial saves; whole-map fallback only for older stock hosts. */
final class SettingsSave {
    static void apply(PluginHost host, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> patch = new HashMap<>();
        for (String key : after.keySet()) if (!Objects.equals(before.get(key), after.get(key))) patch.put(key, after.get(key));
        if (patch.isEmpty()) return;
        try { host.updateSettings(patch); }
        catch (LinkageError | UnsupportedOperationException ignored) { host.saveSettings(after); }
    }
}
