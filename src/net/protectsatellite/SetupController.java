// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import java.util.concurrent.*;

/** Validation and discovery shared by native setup and stock KS settings. */
final class SetupController implements AutoCloseable {
    interface Listener { void changed(State state); }
    interface Save { boolean apply(Map<String, Object> values); }
    interface Connection extends AutoCloseable {
        CertificateProbe.Offer certificate() throws Exception;
        List<DiscoveredCamera> cameras() throws Exception;
        List<String> streams(String cameraId) throws Exception;
        void close();
    }
    interface Factory { Connection open(Settings settings) throws Exception; }
    static final Factory NETWORK = settings -> new Connection() {
        final CertificateProbe probe = new CertificateProbe();
        final ProtectApi api = new ProtectApi(settings);
        public CertificateProbe.Offer certificate() throws Exception { return probe.check(settings); }
        public List<DiscoveredCamera> cameras() throws Exception { return DiscoveredCamera.parse(api.cameras()); }
        public List<String> streams(String cameraId) throws Exception { return StreamChoices.enabled(api.streams(cameraId), settings); }
        public void close() { probe.close(); api.close(); }
    };
    static final class State {
        final String message;
        final boolean busy, error;
        final CertificateProbe.Offer certificate;
        final List<DiscoveredCamera> cameras;
        final String selectedCamera, quality;
        final List<String> streams;
        State(String message, boolean busy, boolean error, CertificateProbe.Offer certificate, List<DiscoveredCamera> cameras) {
            this(message, busy, error, certificate, cameras, "", Collections.emptyList(), "");
        }
        State(String message, boolean busy, boolean error, CertificateProbe.Offer certificate, List<DiscoveredCamera> cameras, String selectedCamera, List<String> streams, String quality) {
            this.message = message; this.busy = busy; this.error = error; this.certificate = certificate; this.cameras = cameras;
            this.selectedCamera = selectedCamera; this.streams = streams; this.quality = quality;
        }
    }
    private final Save save; private final Listener listener; private final Factory factory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "protect-setup"); t.setDaemon(true); return t; });
    private Map<String, Object> draft;
    private State state = new State("Enter your console hostname and API key, then Validate. No microphone is opened during setup.", false, false, null, Collections.emptyList());
    private int generation; private boolean closed; private Connection active;
    private boolean preferencesOnly, validated;
    SetupController(Map<String, Object> values, Save save, Listener listener) { this(values, save, listener, NETWORK); }
    SetupController(Map<String, Object> values, Save save, Listener listener, Factory factory) {
        draft = new HashMap<>(values); this.save = save; this.listener = listener; this.factory = factory;
        try { new Settings(draft).requireCamera(); preferencesOnly = true;
            state = new State("Validate the connection to edit your doorbell settings", false, false, null, Collections.emptyList());
        } catch (Exception ignored) {}
    }
    synchronized State state() { return state; }
    synchronized boolean validated() { return validated; }
    private void publish(State next) { state = next; listener.changed(next); }
    synchronized void invalidate() {
        if (closed) return; preferencesOnly = false; validated = false; cancel();
        publish(new State("Connection details changed — validate again", false, false, null, Collections.emptyList()));
    }
    private void cancel() { generation++; if (active != null) { active.close(); active = null; } }
    synchronized void validate(String console, String key) {
        if (closed) return; preferencesOnly = false; validated = false; cancel();
        try {
            Map<String, Object> next = new HashMap<>(draft); next.put("console", console.trim()); next.put("apiKey", key);
            // Changing the endpoint revokes any old certificate approval.
            Map<String, Object> withoutPin = new HashMap<>(next); withoutPin.put("certificatePin", ""); withoutPin.put("certificatePinConsole", "");
            Settings candidate = new Settings(withoutPin);
            if (!candidate.console.equals(new Settings(draft).console)) {
                next.put("certificatePin", ""); next.put("certificatePinConsole", ""); next.put("cameraId", ""); next.put("cameraAddress", "");
            }
            Settings settings = new Settings(next); settings.requireConnection(); draft = next;
            begin(settings);
        } catch (Exception e) { publish(new State(Failures.describe(e, "Invalid connection details"), false, true, null, Collections.emptyList())); }
    }
    private void begin(Settings settings) {
        final int token = generation;
        publish(new State("Validating HTTPS certificate and Protect access…", true, false, null, Collections.emptyList()));
        worker.submit(() -> {
            Connection connection = null;
            try {
                connection = factory.open(settings);
                synchronized (this) { if (closed || token != generation) return; active = connection; }
                CertificateProbe.Offer offer = connection.certificate();
                if (offer != null) {
                    synchronized (this) { if (!closed && token == generation) publish(new State("Self-signed certificate — approval required before sending credentials", false, false, offer, Collections.emptyList())); }
                    return;
                }
                List<DiscoveredCamera> cameras = connection.cameras();
                synchronized (this) {
                    if (closed || token != generation) return;
                    validated = true;
                    publish(new State(cameras.isEmpty() ? "Connection validated, but no UniFi doorbells were returned" : "Connection validated — select a doorbell and save", false, false, null, cameras));
                }
            } catch (Exception e) {
                synchronized (this) { if (!closed && token == generation) publish(new State(Failures.describe(e, "Setup failed; check the certificate, API key and permissions"), false, true, null, Collections.emptyList())); }
            } finally { if (connection != null) connection.close(); synchronized (this) { if (active == connection) active = null; } }
        });
    }
    synchronized void acceptCertificate(String fingerprint) {
        if (closed || state.certificate == null || !state.certificate.fingerprint.equals(fingerprint)
                || !state.certificate.origin.equals(new Settings(draft).console.toString())) return;
        draft.put("certificatePin", fingerprint); draft.put("certificatePinConsole", state.certificate.origin);
        cancel(); begin(new Settings(draft)); // Reconnect and enforce exact pin before HTTP.
    }
    synchronized boolean select(String cameraId, String manualAddress) {
        if (closed || state.busy || state.error || state.certificate != null) return false;
        if (!cameraId.equals(state.selectedCamera) || !state.streams.contains(state.quality)) return false;
        for (DiscoveredCamera camera : state.cameras) if (camera.id.equals(cameraId)) {
            Map<String, Object> selected = camera.select(draft);
            selected.put("cameraAddress", camera.id.equals(draft.get("cameraId")) ? manualAddress.trim() : "");
            selected.put("quality", state.quality);
            try {
                new Settings(selected).requireCamera();
                if (!save.apply(Collections.unmodifiableMap(selected))) { publish(new State("Settings changed elsewhere; close and reopen setup", false, true, null, Collections.emptyList())); return false; }
                draft = selected; return true;
            } catch (Exception e) { publish(new State(Failures.describe(e, "Could not save doorbell settings"), false, true, null, state.cameras, state.selectedCamera, state.streams, state.quality)); return false; }
        }
        return false;
    }
    synchronized void chooseQuality(String quality) {
        if (closed || state.busy || state.error || !quality.isEmpty() && !state.streams.contains(quality)) return;
        publish(new State(quality.isEmpty() ? "Choose an enabled stream" : "Ready — save and open doorbell", false, false, null, state.cameras, state.selectedCamera, state.streams, quality));
    }
    synchronized void chooseCamera(String cameraId) {
        if (closed || state.certificate != null) return;
        boolean listed = false; for (DiscoveredCamera camera : state.cameras) if (camera.id.equals(cameraId)) listed = true;
        if (!listed || cameraId.equals(state.selectedCamera) && (state.busy || !state.error)) return;
        cancel(); final int token = generation; final List<DiscoveredCamera> cameras = state.cameras;
        Settings settings = new Settings(draft);
        publish(new State("Discovering enabled streams…", true, false, null, cameras, cameraId, Collections.emptyList(), ""));
        worker.submit(() -> {
            Connection connection = null;
            try {
                connection = factory.open(settings);
                synchronized (this) { if (closed || token != generation) return; active = connection; }
                List<String> streams = connection.streams(cameraId);
                synchronized (this) {
                    if (closed || token != generation) return;
                    String saved = new Settings(draft).quality, selected = streams.contains(saved) ? saved : "";
                    publish(new State(streams.isEmpty() ? "No enabled compatible streams. Enable an RTSPS stream in Protect, then validate again."
                        : selected.isEmpty() ? "Choose an enabled stream — saved quality is unavailable" : "Ready — save and open doorbell",
                        false, false, null, cameras, cameraId, streams, selected));
                }
            } catch (Exception e) {
                synchronized (this) { if (!closed && token == generation) publish(new State(Failures.describe(e, "Stream discovery failed"), false, true, null, cameras, cameraId, Collections.emptyList(), "")); }
            } finally { if (connection != null) connection.close(); synchronized (this) { if (active == connection) active = null; } }
        });
    }
    synchronized Map<String, Object> values() { return new HashMap<>(draft); }
    synchronized boolean canSavePreferences() { return !closed && preferencesOnly && !state.busy && !state.error; }
    synchronized void preferences(Map<String, Object> changes) { if (!closed) draft = SetupPreferences.merge(draft, changes); }
    synchronized boolean savePreferences() {
        if (!canSavePreferences()) return false;
        try {
            new Settings(draft).requireCamera();
            if (save.apply(Collections.unmodifiableMap(new HashMap<>(draft)))) return true;
            publish(new State("Settings changed elsewhere; close and reopen setup", false, true, null, Collections.emptyList()));
        } catch (Exception e) { publish(new State(Failures.describe(e, "Could not save settings"), false, true, null, Collections.emptyList())); }
        return false;
    }
    public synchronized void close() { if (closed) return; closed = true; cancel(); worker.shutdownNow(); }
}
