// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Discovery metadata only; no stream/talkback URLs or session creation. */
final class DiscoveredCamera {
    final String id, name, address;
    DiscoveredCamera(String id, String name, String address) { this.id = id; this.name = name; this.address = address; }
    static boolean doorbell(JSONObject camera) {
        JSONObject flags = camera.optJSONObject("featureFlags");
        if (Boolean.TRUE.equals(camera.opt("isDoorbell")) || flags != null && Boolean.TRUE.equals(flags.opt("isDoorbell"))) return true;
        for (String key : new String[] {"type", "model", "modelName", "marketName"}) {
            Object raw = camera.opt(key); if (!(raw instanceof String)) continue;
            String model = ((String) raw).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (model.contains("doorbell") || Arrays.asList("g6entry", "g6proentry", "uvcg6entry", "uvcg6proentry").contains(model)) return true;
        }
        return false; // Never infer hardware type from the editable camera name.
    }
    static List<DiscoveredCamera> parse(JSONArray cameras) throws IOException {
        if (cameras.length() > 256) throw new IOException("Camera discovery exceeds safety limit");
        List<DiscoveredCamera> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.optJSONObject(i); if (camera == null) continue;
            if (!doorbell(camera)) continue;
            String id = camera.optString("id", "");
            if (!id.matches("[A-Za-z0-9_-]{1,128}") || !ids.add(id)) continue;
            String name = DisplayText.singleLine(camera.optString("name", "Doorbell"), 100);
            // Only explicit address fields, never infer an address from a
            // stream URL, a camera ID, or an opened talkback session.
            String address = "";
            for (String key : new String[] {"host", "ipAddress", "ip"}) {
                Object raw = camera.opt(key);
                if (raw instanceof String && ((String) raw).matches("[A-Za-z0-9.:-]{1,253}")) { address = (String) raw; break; }
            }
            result.add(new DiscoveredCamera(id, name.isEmpty() ? "Doorbell" : name, address));
        }
        return Collections.unmodifiableList(result);
    }
    Map<String, Object> select(Map<String, Object> values) {
        Map<String, Object> selected = new HashMap<>(values);
        boolean same = id.equals(values.get("cameraId"));
        selected.put("cameraId", id);
        if (!same) selected.put("cameraAddress", "");
        // An address is learned only on explicit Talk, not during discovery.
        // The same camera preserves its existing learned/manual destination.
        return selected;
    }
}
