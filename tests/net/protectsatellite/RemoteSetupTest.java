// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.PluginHost;

/** Fake KS setup controls; NETWORK tests use only CoreTest's localhost HTTPS server. */
final class RemoteSetupTest {
    static class Host implements PluginHost {
        final Map<String, Object> stored = new ConcurrentHashMap<>();
        final Map<String, List<String>> ids = new ConcurrentHashMap<>();
        final Map<String, String> states = new ConcurrentHashMap<>(), messages = new ConcurrentHashMap<>();
        final Set<String> canceled = ConcurrentHashMap.newKeySet();
        final List<Map<String, Object>> patches = new CopyOnWriteArrayList<>();
        volatile String confirmation = "", details = "";
        public void showWindow(String t, String m, String b) { throw new AssertionError("Remote setup must not open kiosk overlays"); }
        public void hideWindow() {} public void log(String s) { throw new AssertionError("Setup must not log secrets"); }
        public void saveSettings(Map<String, Object> values) { stored.clear(); stored.putAll(values); }
        public void updateSettings(Map<String, Object> patch) { if (!patch.keySet().equals(Collections.singleton("connectionState"))) patches.add(new HashMap<>(patch)); stored.putAll(patch); }
        public boolean setupResult(String run, String state, String message) {
            if (canceled.contains(run)) return false;
            messages.put(run, message); states.put(run, state); return true;
        }
        public boolean confirmSetup(String run, String title, String message, String label) { details = message; confirmation = run; return !canceled.contains(run); }
        public void setSettingChoices(String key, String[] values, String[] labels) { ids.put(key, Arrays.asList(values.clone())); }
    }
    static RemoteSetupAdapter.Save saver(Host host) {
        return (source, patch) -> {
            Map<String, Object> before = new HashMap<>(host.stored);
            if (!TalkbackAddress.current(source, new Settings(before))) return false;
            Map<String, Object> next = new HashMap<>(before); next.putAll(patch); SettingsSave.apply(host, before, next); return true;
        };
    }
    static Map<String, Object> config() { return new HashMap<>(Map.of("console", "console.example", "apiKey", "test-key", "cameraId", "cam1", "quality", "medium", "talkEnabled", true)); }
    static void waitFor(BooleanSupplier ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        if (!ready.getAsBoolean()) throw new AssertionError("Remote setup did not finish");
    }
    static void start(RemoteSetupAdapter adapter, Host host, String id, String key) {
        adapter.event(key.isEmpty() ? "settings.button" : "settings.choices", Map.of("run", id, key.isEmpty() ? "id" : "key", key.isEmpty() ? "validateConnection" : key), new HashMap<>(host.stored));
    }
    static void unit() throws Exception {
        Host host = new Host(); host.stored.putAll(config()); AtomicInteger opens = new AtomicInteger(), reads = new AtomicInteger(), streams = new AtomicInteger();
        SetupController.Factory factory = settings -> { opens.incrementAndGet(); return new SetupController.Connection() {
            public CertificateProbe.Offer certificate() { return null; }
            public List<DiscoveredCamera> cameras() { reads.incrementAndGet(); return Arrays.asList(new DiscoveredCamera("cam1", "Doorbell", ""), new DiscoveredCamera("cam2", "Garage", "")); }
            public List<String> streams(String camera) { streams.incrementAndGet(); return Arrays.asList("low", "medium"); }
            public void close() {}
        }; };
        try (RemoteSetupAdapter adapter = new RemoteSetupAdapter(host, host.stored, saver(host), factory)) {
            CoreTest.check(opens.get() == 0 && host.ids.get("cameraId").isEmpty(), "Startup publishes no assumed availability and does not access Protect");
            CoreTest.check("unvalidated".equals(host.stored.get("connectionState")), "Startup hides dependent settings until real validation");
            start(adapter, host, "validation", ""); waitFor(() -> "success".equals(host.states.get("validation")));
            CoreTest.check(host.ids.get("cameraId").equals(Arrays.asList("cam1", "cam2")) && host.ids.get("quality").equals(Arrays.asList("low", "medium")), "New stock settings buttons discover camera and only enabled streams");
            CoreTest.check(host.patches.isEmpty() && host.stored.get("quality").equals("medium"), "Discovery never changes selected quality or unrelated secrets/preferences");
            CoreTest.check("validated".equals(host.stored.get("connectionState")), "Successful authenticated discovery reveals settings");
            start(adapter, host, "status-refresh", "connectionState"); waitFor(() -> "success".equals(host.states.get("status-refresh")));
            CoreTest.check("validated".equals(host.stored.get("connectionState")), "Connection status Refresh performs real validation rather than leaving a host run unanswered");
            host.stored.put("quality", "high"); start(adapter, host, "streams", "quality"); waitFor(() -> "success".equals(host.states.get("streams")));
            CoreTest.check(host.stored.get("quality").equals("high") && !host.ids.get("quality").contains("high"), "Unavailable saved quality stays saved with picker warning, never silently falls back");
            host.stored.put("cameraId", "cam2"); adapter.configured(host.stored);
            CoreTest.check(host.ids.get("quality").isEmpty() && !host.ids.get("cameraId").isEmpty(), "Camera change clears old-camera stream choices");
            host.stored.put("apiKey", "replacement-test-key"); adapter.configured(host.stored);
            CoreTest.check(host.ids.get("cameraId").isEmpty(), "Credential change invalidates old camera choices");
            CoreTest.check("unvalidated".equals(host.stored.get("connectionState")), "Credential change hides dependent settings again");
            host.canceled.add("expired"); int before = opens.get(); start(adapter, host, "expired", "cameraId");
            CoreTest.check(opens.get() == before, "Expired/rejected host run opens no connection");
        }
        Host unselected = new Host(); unselected.stored.putAll(config()); unselected.stored.remove("cameraId");
        try (RemoteSetupAdapter adapter = new RemoteSetupAdapter(unselected, unselected.stored, saver(unselected), factory)) {
            int before = streams.get(); start(adapter, unselected, "initial", "cameraId"); waitFor(() -> "success".equals(unselected.states.get("initial")));
            CoreTest.check(unselected.ids.get("cameraId").size() == 2 && unselected.ids.get("quality").isEmpty() && streams.get() == before, "First setup discovers cameras but cannot query a stream before camera selection");
            start(adapter, unselected, "no-camera", "quality"); CoreTest.check("error".equals(unselected.states.get("no-camera")), "Stream refresh requires a camera selection");
        }
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        SetupController.Factory slow = settings -> new SetupController.Connection() {
            public CertificateProbe.Offer certificate() { return null; }
            public List<DiscoveredCamera> cameras() throws Exception { blocked.countDown(); release.await(4, TimeUnit.SECONDS); return Collections.singletonList(new DiscoveredCamera("cam1", "Old", "")); }
            public List<String> streams(String id) { return Collections.singletonList("medium"); }
            public void close() { release.countDown(); }
        };
        Host cancel = new Host(); cancel.stored.putAll(config());
        try (RemoteSetupAdapter adapter = new RemoteSetupAdapter(cancel, cancel.stored, saver(cancel), slow)) {
            start(adapter, cancel, "canceled", "cameraId"); CoreTest.check(blocked.await(3, TimeUnit.SECONDS), "Discovery starts off the SDK callback thread");
            cancel.canceled.add("canceled"); adapter.event("settings.cancel", Map.of("run", "canceled", "reason", "canceled"), cancel.stored); Thread.sleep(80);
            CoreTest.check(cancel.ids.get("cameraId").isEmpty() && cancel.patches.isEmpty(), "Cancellation rejects late choices and writes");
        }
        Host old = new Host() {
            public void setSettingChoices(String k, String[] i, String[] l) { throw new NoSuchMethodError("Old SDK"); }
            public void updateSettings(Map<String, Object> patch) { throw new NoSuchMethodError("Old SDK"); }
        }; old.stored.putAll(config());
        try (RemoteSetupAdapter adapter = new RemoteSetupAdapter(old, old.stored, saver(old), factory)) {
            CoreTest.check(!adapter.event("other.event", Collections.emptyMap(), old.stored), "Old hosts keep native setup without guessed API probes");
        }
        Map<String, Object> next = new HashMap<>(old.stored); next.put("autoClose", "Off"); SettingsSave.apply(old, old.stored, next);
        CoreTest.check(old.stored.get("apiKey").equals("test-key") && old.stored.get("autoClose").equals("Off"), "Old-host full-save fallback preserves secrets");
        Host partial = new Host(); partial.stored.putAll(config()); Map<String, Object> before = new HashMap<>(partial.stored); next = new HashMap<>(before); next.put("cameraAddress", "10.0.0.10");
        partial.stored.put("vertical", 73); SettingsSave.apply(partial, before, next);
        CoreTest.check(partial.patches.size() == 1 && partial.patches.get(0).keySet().equals(Collections.singleton("cameraAddress")) && partial.stored.get("vertical").equals(73), "Partial saves cannot overwrite a concurrent unrelated setting");
        Host uncertain = new Host() {
            public void updateSettings(Map<String, Object> patch) { stored.putAll(patch); throw new IllegalStateException("Unverified save"); }
            public void saveSettings(Map<String, Object> all) { throw new AssertionError("Never retry an uncertain partial write as a full write"); }
        }; uncertain.stored.putAll(config());
        Map<String, Object> uncertainNext = new HashMap<>(uncertain.stored); uncertainNext.put("autoClose", "Off");
        CoreTest.rejects(() -> SettingsSave.apply(uncertain, uncertain.stored, uncertainNext), "Uncertain writes propagate without a whole-map retry");
    }
    static void network(Map<String, Object> original, AtomicInteger requests) throws Exception {
        Host host = new Host(); host.stored.putAll(original); host.stored.put("certificatePin", ""); host.stored.put("certificatePinConsole", ""); host.stored.put("vertical", 65);
        int before = requests.get();
        try (RemoteSetupAdapter adapter = new RemoteSetupAdapter(host, host.stored, saver(host))) {
            start(adapter, host, "declined", ""); waitFor(() -> host.confirmation.equals("declined"));
            CoreTest.check(requests.get() == before && host.patches.isEmpty() && host.details.startsWith("SHA-256: " + original.get("certificatePin")) && host.details.length() <= 1000
                && !host.details.contains((String) host.stored.get("apiKey")), "Remote certificate preflight sends no API key and always shows the full fingerprint within the UI limit");
            adapter.event("settings.confirm", Map.of("run", "wrong-run", "accepted", true), host.stored);
            CoreTest.check(requests.get() == before, "Wrong confirmation run cannot approve trust");
            adapter.event("settings.confirm", Map.of("run", "declined", "accepted", false), host.stored);
            CoreTest.check("error".equals(host.states.get("declined")) && requests.get() == before && host.patches.isEmpty(), "Declined trust ends without credentials or persistence");
            start(adapter, host, "cancel-cert", ""); waitFor(() -> host.confirmation.equals("cancel-cert")); host.canceled.add("cancel-cert");
            adapter.event("settings.cancel", Map.of("run", "cancel-cert", "reason", "canceled"), host.stored);
            adapter.event("settings.confirm", Map.of("run", "cancel-cert", "accepted", true), host.stored);
            CoreTest.check(requests.get() == before && host.patches.isEmpty(), "Late confirmation after cancellation cannot trust a certificate or send credentials");
            start(adapter, host, "approved", ""); waitFor(() -> host.confirmation.equals("approved"));
            adapter.event("settings.confirm", Map.of("run", "approved", "accepted", true), host.stored);
            waitFor(() -> "success".equals(host.states.get("approved")));
            CoreTest.check(host.ids.get("quality").equals(Collections.singletonList("medium")), "Remote approved TLS discovers real localhost mock API stream choices");
            CoreTest.check(host.patches.size() == 1 && host.patches.get(0).keySet().equals(new HashSet<>(Arrays.asList("certificatePin", "certificatePinConsole")))
                && host.stored.get("certificatePin").equals(original.get("certificatePin")) && host.stored.get("vertical").equals(65), "Only explicitly approved certificate fields persist, preserving secrets and unrelated settings");
            CoreTest.check(host.stored.get("apiKey").equals(original.get("apiKey")), "Remote setup never replaces the saved API key");
        }
    }
}
