// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import me.jxl.kiosk.plugins.*;

/** Plugin lifecycle/remote trigger tests; never instantiate Android views or media. */
final class PluginTest {
    static final class Host implements PluginHost {
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile Map<String, Object> saved;
        volatile OverlaySpec overlay;
        volatile String overlayKey;
        public void status(String text, boolean error) { CoreTest.check(text.length() <= 1000, "Host status length"); messages.add(text); }
        public void saveSettings(Map<String, Object> values) { saved = new HashMap<>(values); }
        public void showWindow(String title, String text, String button) { throw new AssertionError("No diagnostic windows in the release plugin"); }
        public void hideWindow() { throw new AssertionError("No legacy diagnostic windows to close"); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { overlay = spec; overlayKey = key; }
        public void hideOverlay(String key) { overlay = null; }
        public void log(String text) { throw new AssertionError("Never log settings or secret-bearing exceptions"); }
    }
    static int run(Map<String, Object> connection) throws Exception {
        int checks = 0;
        Host h = new Host(); ProtectPlugin plugin = new ProtectPlugin();
        Map<String, Object> config = new HashMap<>(connection); config.put("vertical", 65); config.put("talkEnabled", false);
        plugin.start(h, config);
        try {
            if (h.messages.size() != 1 || !h.messages.get(0).startsWith("Open Protect doorbell")) throw new AssertionError("Startup should provide normal setup guidance only"); checks++;
            plugin.configure(config);
            if (h.saved != null) throw new AssertionError("Ordinary configure must not rewrite saved preferences"); checks++;
            plugin.execute("setup", Collections.emptyMap());
            if (h.overlay == null || !h.overlay.closeOnBack()) throw new AssertionError("Setup must use a stock dismissible overlay"); checks++;
            CoreTest.rejects(() -> plugin.execute("diagnose", Collections.emptyMap()), "Removed diagnostic command cannot run");
            plugin.execute("show", Collections.emptyMap());
            if (!"camera".equals(h.overlayKey) || h.overlay == null || h.overlay.width() != OverlaySpec.FILL || h.overlay.height() != OverlaySpec.FILL || !h.overlay.closeOnBack())
                throw new AssertionError("Camera must remain a full-screen dismissible overlay"); checks++;
            java.lang.reflect.Method openSetup = ProtectPlugin.class.getDeclaredMethod("setup", String.class, boolean.class); openSetup.setAccessible(true);
            openSetup.invoke(plugin, "", true);
            if (!"setup".equals(h.overlayKey)) throw new AssertionError("Viewer settings opens setup"); checks++;
            plugin.onEvent("overlay.closed", Collections.singletonMap("key", "setup"));
            if (!"camera".equals(h.overlayKey)) throw new AssertionError("Settings Back returns to configured viewer"); checks++;
            openSetup.invoke(plugin, "", true);
            java.lang.reflect.Field setupToken = ProtectPlugin.class.getDeclaredField("setupToken"); setupToken.setAccessible(true);
            java.lang.reflect.Method closeSetup = ProtectPlugin.class.getDeclaredMethod("closeSetup", int.class, boolean.class); closeSetup.setAccessible(true);
            closeSetup.invoke(plugin, setupToken.getInt(plugin), true);
            if (!"camera".equals(h.overlayKey)) throw new AssertionError("Settings Close returns to viewer"); checks++;
            // Exercise the worker persistence callback without Android media or
            // microphone APIs. The real callback receives only a validated IP.
            java.lang.reflect.Field generation = ProtectPlugin.class.getDeclaredField("generation"); generation.setAccessible(true);
            int token = ((java.util.concurrent.atomic.AtomicInteger) generation.get(plugin)).get();
            java.lang.reflect.Method remember = ProtectPlugin.class.getDeclaredMethod("rememberAddress", Settings.class, String.class, int.class); remember.setAccessible(true);
            if (!Boolean.TRUE.equals(remember.invoke(plugin, new Settings(config), "10.0.1.20", token)))
                throw new AssertionError("Current Talk destination should save without prompting"); checks++;
            if (!"10.0.1.20".equals(h.saved.get("cameraAddress")) || !config.get("apiKey").equals(h.saved.get("apiKey")) || !Integer.valueOf(65).equals(h.saved.get("vertical")))
                throw new AssertionError("Learned destination save lost unrelated settings"); checks++;
            if (h.saved.values().stream().anyMatch(v -> String.valueOf(v).startsWith("rtp://") || String.valueOf(v).startsWith("udp://")))
                throw new AssertionError("Must not save session URLs"); checks++;
            plugin.execute("hide", Collections.emptyMap());
            if (h.overlay != null) throw new AssertionError("Closing should remove camera overlay"); checks++;
            if (!Boolean.FALSE.equals(remember.invoke(plugin, new Settings(config), "10.0.1.99", token)) || !"10.0.1.20".equals(h.saved.get("cameraAddress")))
                throw new AssertionError("Cancelled viewer must reject late destination saves"); checks++;
            // Changing consoles revokes a previously accepted/manual API pin.
            config.put("console", "different-console.example"); config.put("certificatePinConsole", connection.get("console"));
            plugin.configure(config);
            if (h.saved == null || !"".equals(h.saved.get("certificatePin")) || !"".equals(h.saved.get("certificatePinConsole")))
                throw new AssertionError("Console change must revoke certificate approval"); checks++;
            if (!connection.get("apiKey").equals(h.saved.get("apiKey")) || !Integer.valueOf(65).equals(h.saved.get("vertical")))
                throw new AssertionError("Console change dropped unrelated settings/secret"); checks++;
            plugin.execute("show", Collections.emptyMap());
            if (!"setup".equals(h.overlayKey)) throw new AssertionError("Open must route an unconfigured camera to setup"); checks++;
            Host restarted = new Host(); ProtectPlugin again = new ProtectPlugin();
            again.start(restarted, connection);
            try {
                if (restarted.saved != null || restarted.messages.size() != 1)
                    throw new AssertionError("Startup must not test or change settings"); checks++;
            } finally { again.stop(); }
        } finally { plugin.stop(); }
        return checks;
    }
}
