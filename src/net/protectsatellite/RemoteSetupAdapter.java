// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import java.util.concurrent.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Documented SDK 1 settings.button/choices/confirm/cancel binding.
 * Discovery uses the same HTTPS/certificate workflow as native setup, never media.
 */
final class RemoteSetupAdapter implements AutoCloseable {
    interface Save { boolean apply(Settings source, Map<String, Object> patch); }
    private PluginHost host;
    private final Save save;
    private final SetupController.Factory factory;
    private Settings known;
    private boolean closed;
    private boolean connected;
    private boolean controlsAvailable;
    private Run active;
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "protect-remote-setup"); t.setDaemon(true); return t; });
    private static final class Run {
        final String id; final Settings source; final boolean streams;
        SetupController controller;
        boolean waitingConfirmation, discoveringStreams;
        Run(String id, Settings source, boolean streams) { this.id = id; this.source = source; this.streams = streams; }
    }
    RemoteSetupAdapter(PluginHost host, Map<String, Object> values, Save save) { this(host, values, save, SetupController.NETWORK); }
    RemoteSetupAdapter(PluginHost host, Map<String, Object> values, Save save, SetupController.Factory factory) {
        this.host = host; this.save = save; this.factory = factory; known = new Settings(values);
        choices("cameraId", Collections.emptyList(), Collections.emptyList()); choices("quality", Collections.emptyList(), Collections.emptyList());
        connectionState(known, false);
    }
    private void connectionState(Settings source, boolean ready) {
        if (!controlsAvailable) return;
        if (!save.apply(source, Collections.singletonMap("connectionState", ready ? "validated" : "unvalidated"))) return;
        connected = ready;
        choices("connectionState", Collections.singletonList(ready ? "validated" : "unvalidated"), Collections.singletonList(ready ? "Connected" : "Validate connection to continue"));
    }
    private void choices(String key, List<String> ids, List<String> labels) {
        if (host == null) return;
        try { host.setSettingChoices(key, ids.toArray(new String[0]), labels.toArray(new String[0])); controlsAvailable = true; }
        catch (LinkageError | UnsupportedOperationException ignored) { /* Old stock KS: keep native setup. */ }
    }
    synchronized void discovered(Settings source, List<DiscoveredCamera> cameras, List<String> streams) {
        if (closed || active != null || !save.apply(source, Collections.emptyMap())) return;
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        for (DiscoveredCamera camera : cameras) { if (ids.size() == 200) break; ids.add(camera.id); labels.add(DisplayText.singleLine(camera.name, 80)); }
        choices("cameraId", ids, labels); choices("quality", streams, streams); connectionState(source, true);
    }
    synchronized void failed(Settings source) {
        if (closed || active != null || !save.apply(source, Collections.emptyMap())) return;
        connectionState(source, false); choices("cameraId", Collections.emptyList(), Collections.emptyList()); choices("quality", Collections.emptyList(), Collections.emptyList());
    }
    synchronized void configured(Map<String, Object> values) {
        if (closed) return;
        Settings next = new Settings(values);
        boolean connection = !known.console.equals(next.console) || !known.apiKey.equals(next.apiKey) || !known.certificatePin.equals(next.certificatePin);
        boolean camera = !known.cameraId.equals(next.cameraId);
        if (connection || camera) {
            end("error", "Connection or doorbell changed — validate again");
            if (connection) choices("cameraId", Collections.emptyList(), Collections.emptyList());
            choices("quality", Collections.emptyList(), Collections.emptyList());
        }
        known = next;
        if (connection) connectionState(next, false);
        else if (!Objects.equals(values.get("connectionState"), connected ? "validated" : "unvalidated")) connectionState(next, connected);
    }
    synchronized boolean event(String name, Map<String, Object> payload, Map<String, Object> values) {
        if (closed) return false;
        String id = Objects.toString(payload.get("run"), "");
        if ("settings.cancel".equals(name)) {
            if (active != null && active.id.equals(id)) abandon(); return true;
        }
        if ("settings.confirm".equals(name)) {
            if (active == null || !active.id.equals(id) || !active.waitingConfirmation) return true;
            Run run = active; run.waitingConfirmation = false;
            if (!Boolean.TRUE.equals(payload.get("accepted"))) { end("error", "Certificate not approved; no credentials sent"); return true; }
            CertificateProbe.Offer offer = run.controller.state().certificate;
            if (offer != null) run.controller.acceptCertificate(offer.fingerprint);
            return true;
        }
        boolean button = "settings.button".equals(name) && "validateConnection".equals(payload.get("id"));
        boolean refresh = "settings.choices".equals(name) && ("cameraId".equals(payload.get("key")) || "quality".equals(payload.get("key")) || "connectionState".equals(payload.get("key")));
        if (!button && !refresh) return false;
        if (id.isEmpty() || id.length() > 128) return true;
        end("error", "Replaced by another setup request");
        Run run = new Run(id, new Settings(values), refresh && "quality".equals(payload.get("key"))); active = run;
        connectionState(run.source, false);
        try {
            run.source.requireConnection(); if (run.streams) run.source.requireCamera();
            if (!progress(run, "Connecting to Protect…")) return true;
            run.controller = new SetupController(values, ignored -> false, state -> enqueue(run, state), factory);
            run.controller.validate(run.source.console.toString(), run.source.apiKey);
        } catch (Exception e) { end("error", Failures.describe(e, "Setup could not start")); }
        return true;
    }
    private void enqueue(Run run, SetupController.State state) {
        try { events.execute(() -> {
            synchronized (RemoteSetupAdapter.this) {
                if (closed || active != run || run.controller.state() != state) return;
                try { receive(run, state); }
                catch (RuntimeException | LinkageError ignored) { end("error", "Setup controls are unavailable; use the viewer's native Setup screen"); }
            }
        }); } catch (RejectedExecutionException ignored) {}
    }
    private boolean progress(Run run, String message) {
        if (host == null || active != run) return false;
        try { if (host.setupResult(run.id, "running", DisplayText.limit(message, 1000))) return true; }
        catch (RuntimeException | LinkageError ignored) {}
        abandon(); return false;
    }
    private void receive(Run run, SetupController.State state) {
        if (state.busy) { progress(run, state.message); return; }
        if (state.error) { end("error", state.message); return; }
        if (state.certificate != null) {
            if (!progress(run, "Certificate approval required before sending the API key")) return;
            run.waitingConfirmation = true;
            String trust = "SHA-256: " + state.certificate.fingerprint + "\nConsole: " + DisplayText.limit(state.certificate.origin, 240)
                + "\n\n" + DisplayText.limit(state.certificate.details, 540)
                + "\n\nVerify this fingerprint independently. Trust only this exact certificate for this console.";
            if (!host.confirmSetup(run.id, "Trust Protect certificate?", trust, "Trust certificate")) abandon();
            return;
        }
        if (!run.discoveringStreams && !run.source.cameraId.isEmpty()) {
            boolean present = false; for (DiscoveredCamera camera : state.cameras) if (camera.id.equals(run.source.cameraId)) present = true;
            if (present) { run.discoveringStreams = true; run.controller.chooseCamera(run.source.cameraId); return; }
            if (run.streams) { choices("quality", Collections.emptyList(), Collections.emptyList()); end("error", "Doorbell unavailable. Validate and choose an available doorbell."); return; }
        }
        if (!progress(run, "Publishing discovered choices…")) return;
        Map<String, Object> draft = run.controller.values(); Settings validated = new Settings(draft);
        Map<String, Object> patch = new HashMap<>();
        if (!validated.certificatePin.equals(run.source.certificatePin)) {
            patch.put("certificatePin", draft.get("certificatePin")); patch.put("certificatePinConsole", draft.get("certificatePinConsole"));
        }
        // Scope guard runs even for an empty patch; never publish old-camera choices.
        if (!save.apply(run.source, patch)) { end("error", "Settings changed elsewhere; run setup again"); return; }
        known = validated;
        connectionState(validated, true);
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        for (DiscoveredCamera camera : state.cameras) {
            if (ids.size() == 200) break;
            ids.add(camera.id); labels.add(DisplayText.singleLine(camera.name, 80));
        }
        choices("cameraId", ids, labels);
        List<String> qualities = new ArrayList<>();
        if (run.discoveringStreams) for (String quality : state.streams) if (Arrays.asList("low", "medium", "high").contains(quality)) qualities.add(quality);
        choices("quality", qualities, qualities);
        if (ids.isEmpty()) end("error", "Protect validated, but no UniFi doorbells were returned");
        else if (run.discoveringStreams && qualities.isEmpty()) end("error", "No compatible streams. Enable an RTSPS stream in Protect, then Refresh.");
        else end("success", run.discoveringStreams ? "Connected. Available streams: " + String.join(", ", qualities) + ". Select your preferred stream."
            : "Connected. Found " + ids.size() + " doorbell(s). Choose one, then Refresh the stream picker.");
    }
    private void abandon() { Run old = active; active = null; if (old != null && old.controller != null) old.controller.close(); }
    private void end(String state, String message) {
        Run old = active; if (old == null) return;
        if (host != null) try { host.setupResult(old.id, state, DisplayText.limit(message, 1000)); } catch (RuntimeException | LinkageError ignored) {}
        abandon();
    }
    public synchronized void close() { closed = true; host = null; abandon(); events.shutdownNow(); }
}
