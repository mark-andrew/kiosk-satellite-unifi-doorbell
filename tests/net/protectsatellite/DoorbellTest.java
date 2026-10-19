// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import org.json.*;

final class DoorbellTest {
    static void run() throws Exception {
        for (String model : new String[] {"G4 Doorbell", "G4 Doorbell Pro", "G4 Doorbell Pro PoE", "UVC-G4-Doorbell", "G6 Entry", "G6 Pro Entry", "UVC-G6-Entry"})
            CoreTest.check(DiscoveredCamera.doorbell(new JSONObject().put("type", model).put("name", "Front entrance")), "Hardware model accepted: " + model);
        for (String model : new String[] {"G5 Bullet", "G6 Turret", "AI Pro", "AI 360", "G4 Instant", "G3 Flex", "G4 PTZ", "Intercom", "camera", ""})
            CoreTest.check(!DiscoveredCamera.doorbell(new JSONObject().put("type", model).put("name", "G4 Doorbell Pro")), "Editable name cannot include non-doorbell: " + model);
        CoreTest.check(!DiscoveredCamera.doorbell(new JSONObject().put("hasPackageCamera", true)), "Package camera alone is not proof of doorbell hardware");
        CoreTest.check(DiscoveredCamera.doorbell(new JSONObject().put("featureFlags", new JSONObject().put("isDoorbell", true))), "Explicit reported doorbell capability is supported");
        CoreTest.check(!DiscoveredCamera.doorbell(new JSONObject().put("isDoorbell", "true")), "String values cannot spoof a typed doorbell capability");
        JSONArray mixed = new JSONArray().put(new JSONObject().put("id", "bell").put("type", "G4 Doorbell").put("name", "Front"))
            .put(new JSONObject().put("id", "bullet").put("type", "G5 Bullet").put("name", "Doorbell"))
            .put(new JSONObject().put("id", "unknown").put("name", "Doorbell"));
        List<DiscoveredCamera> parsed = DiscoveredCamera.parse(mixed);
        CoreTest.check(parsed.size() == 1 && parsed.get(0).id.equals("bell"), "Discovery filters ordinary and unidentified cameras before either setup renderer");
        Map<String, Object> values = RemoteSetupTest.config(); RemoteSetupTest.Host host = new RemoteSetupTest.Host(); host.stored.putAll(values);
        try (RemoteSetupAdapter remote = new RemoteSetupAdapter(host, host.stored, RemoteSetupTest.saver(host))) {
            Settings source = new Settings(host.stored);
            remote.discovered(source, parsed, Arrays.asList("low", "medium"));
            CoreTest.check(host.ids.get("cameraId").equals(Collections.singletonList("bell")) && "validated".equals(host.stored.get("connectionState")), "Viewer refresh publishes current doorbells and validation status");
            CoreTest.check(host.ids.get("quality").equals(Arrays.asList("low", "medium")) && host.stored.get("quality").equals("medium"), "Viewer refresh updates availability without selecting quality");
            host.stored.put("console", "new-console.example"); remote.configured(host.stored);
            remote.discovered(source, parsed, Collections.singletonList("high"));
            CoreTest.check(host.ids.get("quality").isEmpty() && "unvalidated".equals(host.stored.get("connectionState")), "Late old-viewer discovery cannot reveal settings for a new console");
            remote.discovered(new Settings(host.stored), parsed, Collections.singletonList("medium"));
            remote.failed(new Settings(host.stored));
            CoreTest.check("unvalidated".equals(host.stored.get("connectionState")) && host.ids.get("cameraId").isEmpty(), "Viewer connection failure hides settings and clears stale choices");
        }
    }
}
