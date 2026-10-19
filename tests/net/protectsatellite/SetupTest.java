// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

final class SetupTest {
    static void unit() throws Exception {
        Map<String, Object> values = new HashMap<>(); values.put("console", "console.example:443"); values.put("apiKey", "private-test-key");
        CoreTest.check(new Settings(values).console.toString().equals("https://console.example:443"), "Bare hostname gets HTTPS");
        values.put("console", "10.0.1.1"); CoreTest.check(new Settings(values).console.getHost().equals("10.0.1.1"), "Bare IP gets HTTPS");
        values.put("console", "2001:db8::1"); CoreTest.check(new Settings(values).console.toString().equals("https://[2001:db8::1]"), "Bare IPv6 gets HTTPS and brackets");
        values.put("console", "https://console.example:443");
        CoreTest.check(new Settings(values).console.getPort() == 443, "Existing HTTPS URL stays compatible");
        values.put("certificatePin", "a".repeat(64)); values.put("certificatePinConsole", "https://another-console.example");
        CoreTest.rejects(() -> new Settings(values), "Saved certificate acceptance cannot move between consoles");
        values.remove("certificatePin"); values.remove("certificatePinConsole"); values.put("cameraId", "cam1"); values.put("cameraAddress", "10.0.1.20");
        Settings defaults = new Settings(Collections.emptyMap());
        CoreTest.check(defaults.sizing.equals("Stretch") && defaults.controls.equals("Top") && defaults.lockEntity.isEmpty(), "New setup defaults to Stretch / Top with no lock");
        Map<String, Object> retained = new HashMap<>(values); retained.put("sizing", "Fill"); retained.put("controls", "Bottom");
        CoreTest.check(new Settings(retained).sizing.equals("Fill") && new Settings(retained).controls.equals("Bottom"), "Existing display preferences are not migrated over");
        for (String placement : new String[] {"Top", "Bottom", "Left", "Right"}) {
            retained.put("controls", placement);
            CoreTest.check(new Settings(retained).controls.equals(placement) && ControlsLayout.vertical(placement) == (placement.equals("Left") || placement.equals("Right")), "Supported placement " + placement);
        }
        CoreTest.check(ControlsLayout.gravity("Top") != ControlsLayout.gravity("Bottom") && ControlsLayout.gravity("Left") != ControlsLayout.gravity("Right"), "Each placement anchors to the selected edge");
        CoreTest.rejects(() -> SetupPreferences.merge(values, Collections.singletonMap("console", "other.example")), "Preferences cannot bypass connection validation");
        CoreTest.rejects(() -> SetupPreferences.merge(values, Collections.singletonMap("lockEntity", "switch.front")), "Lock picker cannot store another entity domain");
        AtomicReference<Map<String, Object>> preferencesSaved = new AtomicReference<>();
        try (SetupController prefs = new SetupController(retained, selected -> { preferencesSaved.set(new HashMap<>(selected)); return true; }, ignored -> {}, settings -> { throw new AssertionError("Preferences must not access Protect"); })) {
            CoreTest.check(prefs.canSavePreferences(), "Configured camera can edit Display/Audio immediately");
            Map<String, Object> edits = new HashMap<>(); edits.put("controls", "Left"); edits.put("sizing", "Stretch"); edits.put("startMuted", false); edits.put("lockEntity", "lock.front");
            prefs.preferences(edits); CoreTest.check(prefs.savePreferences(), "Preference-only save succeeds without discovery or validation");
            Map<String, Object> saved = preferencesSaved.get();
            CoreTest.check(saved.get("controls").equals("Left") && saved.get("lockEntity").equals("lock.front") && saved.get("apiKey").equals(values.get("apiKey"))
                && saved.get("cameraId").equals("cam1") && saved.get("cameraAddress").equals("10.0.1.20"), "Preference save preserves connection, secrets and learned audio address");
            prefs.invalidate(); CoreTest.check(!prefs.canSavePreferences() && !prefs.savePreferences(), "Connection edit disables preference-only bypass until validation");
        }
        try (SetupController blank = new SetupController(Collections.emptyMap(), selected -> true, ignored -> {})) {
            CoreTest.check(!blank.canSavePreferences() && !blank.savePreferences(), "Unconfigured setup cannot save unvalidated connection");
        }
        List<DiscoveredCamera> cameras = DiscoveredCamera.parse(new JSONArray("[{\"id\":\"cam1\",\"type\":\"G4 Doorbell Pro\",\"name\":\"Doorbell\\nPro\"},{\"id\":\"cam2\",\"type\":\"G4 Doorbell\",\"name\":\"Other\",\"host\":\"10.0.1.21\"},{\"id\":\"../../bad\"},{\"id\":\"cam2\"}]"));
        CoreTest.check(cameras.size() == 2 && !cameras.get(0).name.contains("\n"), "Camera picker sanitizes names and rejects invalid/duplicate IDs");
        CoreTest.check(cameras.get(0).select(values).get("cameraAddress").equals("10.0.1.20"), "Same camera preserves its learned audio address");
        CoreTest.check(cameras.get(1).select(values).get("cameraAddress").equals(""), "Camera change clears the learned address even when discovery metadata contains a host");
        DiscoveredCamera unknown = new DiscoveredCamera("cam3", "Unknown", "");
        CoreTest.check(unknown.select(values).get("cameraAddress").equals(""), "Different camera with unknown address clears stale old-camera IP");
        AtomicInteger reads = new AtomicInteger(), closed = new AtomicInteger(), saves = new AtomicInteger();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        List<SetupController.State> events = new CopyOnWriteArrayList<>();
        SetupController.Factory slow = settings -> new SetupController.Connection() {
            public CertificateProbe.Offer certificate() { return null; }
            public List<DiscoveredCamera> cameras() throws Exception { reads.incrementAndGet(); blocked.countDown(); release.await(4, TimeUnit.SECONDS); return cameras; }
            public List<String> streams(String cameraId) { return Arrays.asList("low", "medium"); }
            public void close() { closed.incrementAndGet(); release.countDown(); }
        };
        SetupController controller = new SetupController(values, selected -> { saves.incrementAndGet(); return true; }, events::add, slow);
        controller.validate("console.example:443", "private-test-key");
        CoreTest.check(blocked.await(3, TimeUnit.SECONDS), "Discovery starts on worker");
        controller.invalidate();
        Thread.sleep(60);
        CoreTest.check(!controller.state().busy && controller.state().cameras.isEmpty(), "Settings edit rejects late discovery results");
        CoreTest.check(!controller.select("cam1", "10.0.1.20") && saves.get() == 0, "Invalidated discovery cannot save");
        CoreTest.check(closed.get() >= 1, "Invalidation cancels active network work");
        controller.close(); int eventsAtClose = events.size(); controller.validate("console.example", "key");
        CoreTest.check(events.size() == eventsAtClose && reads.get() == 1, "Closed controller cannot restart or publish");

        SetupController.Factory immediate = settings -> new SetupController.Connection() {
            public CertificateProbe.Offer certificate() { return null; }
            public List<DiscoveredCamera> cameras() { return cameras; }
            public List<String> streams(String cameraId) { return Arrays.asList("low", "medium"); }
            public void close() {}
        };
        try (SetupController stale = new SetupController(values, selected -> false, ignored -> {}, immediate)) {
            stale.validate("console.example:443", "private-test-key"); await(stale);
            stale.chooseCamera("cam1"); await(stale);
            CoreTest.check(!stale.select("cam1", "10.0.1.20") && stale.state().error, "Concurrent external edits reject setup save");
        }
        AtomicReference<Map<String, Object>> selectedValues = new AtomicReference<>();
        try (SetupController changed = new SetupController(values, selected -> { selectedValues.set(selected); return true; }, ignored -> {}, immediate)) {
            changed.validate("console.example:443", "private-test-key"); await(changed);
            changed.chooseCamera("cam2"); await(changed);
            CoreTest.check(changed.select("cam2", "10.0.1.20") && selectedValues.get().get("cameraAddress").equals(""), "Setup save cannot carry old-camera destination into a new camera");
        }
        Map<String, Object> high = new HashMap<>(values); high.put("quality", "high");
        try (SetupController streams = new SetupController(high, selected -> { selectedValues.set(selected); return true; }, ignored -> {}, immediate)) {
            streams.validate("console.example:443", "private-test-key"); await(streams);
            CoreTest.check(!streams.select("cam1", ""), "Save disabled before stream discovery");
            streams.chooseCamera("not-listed"); CoreTest.check(streams.state().selectedCamera.isEmpty(), "Cannot query streams of an unlisted camera");
            streams.chooseCamera("cam1"); await(streams);
            CoreTest.check(streams.state().quality.isEmpty() && !streams.select("cam1", ""), "Unavailable saved quality requires explicit selection, not fallback");
            streams.chooseQuality("high"); CoreTest.check(streams.state().quality.isEmpty(), "Cannot choose disabled stream");
            streams.chooseQuality("low");
            CoreTest.check(streams.select("cam1", "10.0.1.20") && selectedValues.get().get("quality").equals("low"), "Explicit enabled stream persists in whole configuration");
            streams.chooseQuality(""); CoreTest.check(!streams.select("cam1", ""), "Placeholder selection disables save again");
            streams.chooseQuality("medium"); streams.invalidate();
            CoreTest.check(!streams.select("cam1", ""), "Connection edit invalidates stream selection");
        }
        JSONObject metadata = new JSONObject().put("low", "rtsps://relay:7441/token").put("medium", JSONObject.NULL).put("high", "rtsp://relay/token");
        CoreTest.check(StreamChoices.enabled(metadata, new Settings(values)).equals(Collections.singletonList("low")), "Offer only existing valid RTSPS stream qualities");
        CoreTest.check(StreamChoices.enabled(new JSONObject(), new Settings(values)).isEmpty(), "No streams: do not invent a default");
        CoreTest.check(MusicFocus.mode(false, false) == 0 && MusicFocus.mode(true, false) == 1 && MusicFocus.mode(true, true) == 2 && MusicFocus.mode(false, true) == 2, "Music policy restores for silent video, ducks listening and prioritizes Talk");
        CountDownLatch streamStarted = new CountDownLatch(1), streamRelease = new CountDownLatch(1);
        SetupController.Factory streamSlow = settings -> new SetupController.Connection() {
            public CertificateProbe.Offer certificate() { return null; }
            public List<DiscoveredCamera> cameras() { return cameras; }
            public List<String> streams(String id) throws Exception { streamStarted.countDown(); streamRelease.await(4, TimeUnit.SECONDS); return Collections.singletonList("high"); }
            public void close() { streamRelease.countDown(); }
        };
        // The camera-list connection closes first; block only the subsequent stream read.
        AtomicInteger opened = new AtomicInteger();
        SetupController.Factory delayedStreams = settings -> opened.incrementAndGet() == 1 ? immediate.open(settings) : streamSlow.open(settings);
        try (SetupController delayed = new SetupController(values, selected -> true, ignored -> {}, delayedStreams)) {
            delayed.validate("console.example:443", "private-test-key"); await(delayed);
            delayed.chooseCamera("cam1"); CoreTest.check(streamStarted.await(3, TimeUnit.SECONDS), "Stream discovery executes off the UI thread");
            delayed.invalidate(); Thread.sleep(60);
            CoreTest.check(delayed.state().streams.isEmpty() && delayed.state().selectedCamera.isEmpty() && !delayed.select("cam1", ""), "Late stream response cannot restore an invalidated selection");
        }
        SetupController.Factory emptyStreams = settings -> new SetupController.Connection() {
            public CertificateProbe.Offer certificate() { return null; }
            public List<DiscoveredCamera> cameras() { return cameras; }
            public List<String> streams(String id) { return Collections.emptyList(); }
            public void close() {}
        };
        try (SetupController empty = new SetupController(values, selected -> true, ignored -> {}, emptyStreams)) {
            empty.validate("console.example:443", "private-test-key"); await(empty); empty.chooseCamera("cam1"); await(empty);
            CoreTest.check(!empty.select("cam1", "") && empty.state().message.contains("Enable an RTSPS stream"), "No available streams explains Protect configuration and prevents save");
        }
    }
    static void network(Map<String, Object> original, AtomicInteger requests) throws Exception {
        RemoteSetupTest.network(original, requests);
        Map<String, Object> values = new HashMap<>(original); values.put("certificatePin", ""); values.put("certificatePinConsole", "");
        values.put("vertical", 65); values.put("talkEnabled", true); values.put("talkLimit", 35);
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        int before = requests.get();
        try (SetupController controller = new SetupController(values, selected -> { saved.set(new HashMap<>(selected)); return true; }, ignored -> {})) {
            controller.validate((String) values.get("console"), (String) values.get("apiKey")); await(controller);
            CertificateProbe.Offer offer = controller.state().certificate;
            CoreTest.check(offer != null && !controller.state().error, "Untrusted self-signed API certificate requires explicit approval");
            CoreTest.check(requests.get() == before && saved.get() == null, "No HTTP/API key or settings write before approval");
            CoreTest.check(offer.details.contains(offer.fingerprint) && !offer.details.contains((String) values.get("apiKey")), "Certificate confirmation shows fingerprint without credentials");
            controller.acceptCertificate("b".repeat(64));
            CoreTest.check(controller.state().certificate == offer && requests.get() == before, "Wrong/stale confirmation fingerprint ignored");
            controller.acceptCertificate(offer.fingerprint); await(controller);
            CoreTest.check(controller.state().cameras.size() == 1 && controller.state().certificate == null, "Explicit pin approval reconnects and discovers cameras");
            CoreTest.check(saved.get() == null, "Successful validation waits for explicit camera save");
            CoreTest.check(!controller.select("not-discovered", "10.0.1.20"), "Unadvertised camera cannot be selected");
            controller.chooseCamera("cam1"); await(controller);
            CoreTest.check(controller.state().streams.equals(Collections.singletonList("medium")), "Setup queries only existing enabled streams");
            CoreTest.check(controller.select("cam1", "10.0.1.20"), "Selected camera saves after validation");
            Map<String, Object> result = saved.get();
            CoreTest.check(result.get("apiKey").equals(values.get("apiKey")) && result.get("vertical").equals(65) && result.get("talkEnabled").equals(true) && result.get("talkLimit").equals(35), "Setup preserves secrets, crop, opt-in and timeout");
            CoreTest.check(result.get("certificatePin").equals(offer.fingerprint) && result.get("certificatePinConsole").equals(offer.origin), "Acceptance persisted with exact endpoint scope");
        }
        values.put("certificatePin", "0".repeat(64));
        before = requests.get();
        try (SetupController changed = new SetupController(values, selected -> false, ignored -> {})) {
            changed.validate((String) values.get("console"), (String) values.get("apiKey")); await(changed);
            CoreTest.check(changed.state().error && changed.state().certificate == null && requests.get() == before, "Changed pinned certificate is rejected, never silently re-offered");
        }
        values.put("certificatePin", original.get("certificatePin"));
        try (SetupController badKey = new SetupController(values, selected -> false, ignored -> {})) {
            badKey.validate((String) values.get("console"), "wrong-key"); await(badKey);
            CoreTest.check(badKey.state().error && badKey.state().message.contains("rejected") && !badKey.state().message.contains("wrong-key"), "API authentication failure visible without key leak");
        }
    }
    private static void await(SetupController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (controller.state().busy && System.nanoTime() < deadline) Thread.sleep(10);
        if (controller.state().busy) throw new AssertionError("Setup worker timed out");
    }
}
