// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.content.Context;
import android.view.View;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.*;

public final class ProtectPlugin implements KioskPlugin {
    private volatile PluginHost host;
    private volatile Settings settings;
    private volatile CameraView view;
    private volatile SetupView setupView;
    private HaLocks locks;
    private volatile int setupToken = -1;
    private volatile boolean setupReturnsToViewer;
    private final Object configLock = new Object();
    private Map<String, Object> values;
    private int configRevision;
    private RemoteSetupAdapter remoteSetup;
    private final AtomicInteger generation = new AtomicInteger();

    public void start(PluginHost host, Map<String, Object> values) {
        this.host = host; settings = new Settings(values); this.values = new java.util.HashMap<>(values);
        locks = new HaLocks(host, new HaLocks.Listener() {
            public void changed(String state, boolean busy) { CameraView current = view; if (current != null) current.lockStatus(state, busy); }
            public void error(String message) { CameraView current = view; if (current != null) current.showError(message); }
        });
        locks.select(settings.lockEntity);
        remoteSetup = new RemoteSetupAdapter(host, this.values, this::saveRemote);
        status("Open Protect doorbell to view or set up your connection");
    }
    public void configure(Map<String, Object> values) {
        Map<String, Object> next = TalkbackAddress.changedSelection(values, settings);
        Settings candidate = new Settings(next); hide();
        synchronized (configLock) { settings = candidate; this.values = next; configRevision++; if (!next.equals(values) && host != null) SettingsSave.apply(host, values, next); }
        remoteSetup.configured(next);
        locks.select(candidate.lockEntity);
        status("Settings saved");
    }
    public void execute(String command, Map<String, Object> arguments) {
        if ("setup".equals(command)) { setup(""); return; } // Legacy internal alias, no separate menu action.
        if ("hide".equals(command)) { hide(); return; }
        if (!"show".equals(command)) throw new IllegalArgumentException("Unknown Protect plugin command");
        Settings snapshot = settings;
        try { snapshot.requireCamera(); } catch (Exception e) { setup(Failures.describe(e, "Configure the connection before opening the viewer")); return; }
        hide();
        final int token = generation.get(); PluginHost h = host; if (h == null) return;
        h.showOverlay("camera", OverlaySpec.fullScreen().closeOnBack(true), new OverlayFactory() {
            public View create(Context context, KsTheme theme) {
                final CameraView[] owner = new CameraView[1];
                CameraView created = new CameraView(context, theme, snapshot,
                    () -> { if (generation.get() == token && view == owner[0]) hide(); },
                    reason -> { if (generation.get() == token && view == owner[0]) { if (!reason.isEmpty()) remoteSetup.failed(snapshot); setup(reason, reason.isEmpty()); } },
                    (source, address) -> view == owner[0] && rememberAddress(source, address, token),
                    () -> { if (generation.get() == token && view == owner[0]) locks.toggle(); },
                    (cameras, streams) -> { if (generation.get() == token && view == owner[0]) remoteSetup.discovered(snapshot, cameras, streams); });
                owner[0] = created;
                if (generation.get() != token || host == null) { created.dispose(); return created; }
                view = created; locks.refresh(); return created;
            }
            public void onDestroy(View destroyed) {
                if (destroyed instanceof CameraView) ((CameraView) destroyed).dispose();
                if (view == destroyed) { view = null; locks.cancelAction(); }
            }
            public void onThemeChanged(View changed, KsTheme theme) { if (changed instanceof CameraView) ((CameraView) changed).applyTheme(theme); }
        });
    }
    private boolean rememberAddress(Settings source, String address, int token) {
        synchronized (configLock) {
            if (host == null || generation.get() != token || !TalkbackAddress.current(source, settings)) return false;
            if (address.equals(settings.cameraAddress)) return true;
            Map<String, Object> next = new java.util.HashMap<>(values); next.put("cameraAddress", address);
            Settings candidate = new Settings(next);
            SettingsSave.apply(host, values, next);
            values = next; settings = candidate; configRevision++;
            return true;
        }
    }
    private boolean saveRemote(Settings source, Map<String, Object> patch) {
        synchronized (configLock) {
            if (host == null || !TalkbackAddress.current(source, settings)) return false;
            if (patch.isEmpty()) return true;
            for (String key : patch.keySet()) if (!"certificatePin".equals(key) && !"certificatePinConsole".equals(key) && !"connectionState".equals(key)) return false;
            Map<String, Object> next = new java.util.HashMap<>(values); next.putAll(patch); Settings candidate = new Settings(next);
            SettingsSave.apply(host, values, next); values = next; settings = candidate; configRevision++; return true;
        }
    }
    private void setup(String reason) {
        setup(reason, false);
    }
    private void setup(String reason, boolean returnToViewer) {
        hide();
        final int token = generation.get(); final int revision; final Map<String, Object> snapshot;
        setupToken = token; setupReturnsToViewer = returnToViewer;
        synchronized (configLock) { revision = configRevision; snapshot = new java.util.HashMap<>(values); }
        PluginHost h = host; if (h == null) return;
        h.showOverlay("setup", OverlaySpec.fullScreen().closeOnBack(true), new OverlayFactory() {
            public View create(Context context, KsTheme theme) {
                final SetupView[] owner = new SetupView[1];
                SetupView created = new SetupView(context, theme, snapshot, reason, selected -> {
                    synchronized (configLock) {
                        if (host == null || setupView != owner[0] || generation.get() != token || configRevision != revision) return false;
                        Settings candidate = new Settings(selected);
                        SettingsSave.apply(host, values, selected);
                        values = new java.util.HashMap<>(selected); settings = candidate; configRevision++;
                        locks.select(candidate.lockEntity);
                        status("Doorbell settings saved");
                    }
                    remoteSetup.configured(selected); return true;
                }, () -> { if (setupView == owner[0]) closeSetup(token, returnToViewer); }, () -> { if (generation.get() == token && setupView == owner[0]) execute("show", java.util.Collections.emptyMap()); },
                    () -> { if (generation.get() == token && setupView == owner[0]) hide(); }, locks);
                owner[0] = created;
                if (generation.get() != token || host == null) { created.dispose(); return created; }
                setupView = created; return created;
            }
            public void onDestroy(View destroyed) {
                if (destroyed instanceof SetupView) ((SetupView) destroyed).dispose();
                if (setupView == destroyed) setupView = null;
            }
            public void onThemeChanged(View changed, KsTheme theme) { if (changed instanceof SetupView) ((SetupView) changed).applyTheme(theme); }
        });
    }
    private void closeSetup(int token, boolean returnToViewer) {
        if (generation.get() != token) return;
        if (returnToViewer) execute("show", java.util.Collections.emptyMap()); else hide();
    }
    private void status(String message) {
        PluginHost h = host; if (h == null) return;
        h.status(DisplayText.limit(message, 1000), false);
    }
    private void hide() {
        setupToken = -1;
        generation.incrementAndGet(); CameraView current = view; view = null;
        if (locks != null) { locks.cancelAction(); locks.closePicker(); }
        if (current != null) { current.stopTransports(); current.post(current::dispose); }
        SetupView setup = setupView; setupView = null;
        if (setup != null) { setup.cancelWork(); setup.post(setup::dispose); }
        PluginHost h = host; if (h != null) { h.hideOverlay("camera"); h.hideOverlay("setup"); }
    }
    public void onEvent(String event, Map<String, Object> payload) {
        if (event.startsWith("settings.") && remoteSetup != null) {
            final Map<String, Object> snapshot;
            synchronized (configLock) { snapshot = new java.util.HashMap<>(values); }
            if (remoteSetup.event(event, payload, snapshot)) return;
        }
        if (locks != null) locks.event(event, payload);
        if ("overlay.closed".equals(event)) {
            if ("setup".equals(payload.get("key"))) closeSetup(setupToken, setupReturnsToViewer);
            else if ("camera".equals(payload.get("key")) && view != null) hide();
        }
    }
    public void stop() {
        // The SDK revokes host access before stop. Resource cleanup must not call it.
        generation.incrementAndGet(); synchronized (configLock) { host = null; configRevision++; } remoteSetup.close();
        if (locks != null) locks.stop();
        SetupView setup = setupView; setupView = null;
        if (setup != null) { setup.cancelWork(); setup.post(setup::dispose); }
        CameraView current = view; view = null; if (current != null) { current.stopTransports(); current.post(current::dispose); }
    }
}
