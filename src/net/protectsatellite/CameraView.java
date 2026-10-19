// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;
import java.util.*;
import android.graphics.drawable.Drawable;
import me.jxl.kiosk.plugins.KsTheme;

/** One native view owns both directions of audio and all its resource lifetimes. */
final class CameraView extends FrameLayout implements TextureView.SurfaceTextureListener, Application.ActivityLifecycleCallbacks {
    private final Settings settings;
    private final Runnable closeAction;
    private final Consumer<String> setupAction;
    private final TalkbackAddress.Save addressSave;
    private final java.util.function.BiConsumer<List<DiscoveredCamera>, List<String>> discovery;
    private final FrameLayout viewport;
    private final TextureView texture;
    private final TextView status;
    private final ImageButton listen, talk, doorLock;
    private KsTheme theme;
    private final List<ImageButton> controls = new ArrayList<>();
    private String lockState = "";
    private boolean lockBusy;
    private final MusicFocus music;
    private boolean statusError, audioInterrupted;
    private final TalkToggle talkState = new TalkToggle();
    private final Application application;
    private volatile StreamPlayer player;
    private volatile Talkback transmitter;
    private volatile boolean stopping;
    private boolean listeningMuted;
    private volatile boolean disposed;
    private final CallbackScope playbackCallbacks = new CallbackScope();
    private int videoWidth = 4, videoHeight = 3;
    private final Runnable talkTimeout = this::endTalk;
    private final IdleTimeout idleTimer;
    private final Runnable idleCheck = this::checkIdle;

    CameraView(Context context, KsTheme theme, Settings settings, Runnable closeAction, Consumer<String> setupAction, TalkbackAddress.Save addressSave, Runnable toggleLock, java.util.function.BiConsumer<List<DiscoveredCamera>, List<String>> discovery) {
        super(context); this.theme = theme; this.settings = settings; this.closeAction = closeAction; this.setupAction = setupAction; this.addressSave = addressSave; listeningMuted = settings.startMuted;
        idleTimer = new IdleTimeout(settings.autoCloseSeconds, android.os.SystemClock.uptimeMillis());
        this.discovery = discovery;
        music = new MusicFocus(context, () -> { audioInterrupted = true; listeningMuted = true; styleListen(); endTalk(); applyMute(); message("Audio interrupted — tap Listen to resume", false); });
        setBackgroundColor(Color.BLACK); setKeepScreenOn(true); setClickable(true);
        viewport = new FrameLayout(context); viewport.setClipChildren(true); addView(viewport, new LayoutParams(-1, -1, Gravity.CENTER));
        texture = new TextureView(context); viewport.addView(texture, new LayoutParams(-1, -1)); texture.setSurfaceTextureListener(this);
        status = new TextView(context); status.setVisibility(GONE); status.setPadding(dp(12), dp(8), dp(12), dp(8));
        LayoutParams statusPosition = new LayoutParams(-2, -2, "Top".equals(settings.controls) ? Gravity.BOTTOM | Gravity.START : Gravity.TOP | Gravity.START);
        statusPosition.setMargins(dp(12), dp(12), dp(12), dp(12)); addView(status, statusPosition);
        LinearLayout buttons = new LinearLayout(context); buttons.setOrientation(ControlsLayout.vertical(settings.controls) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        ImageButton back = button("Close"); back.setOnClickListener(v -> { dispose(); closeAction.run(); }); buttons.addView(back, cell());
        ImageButton setup = button("Setup"); setup.setOnClickListener(v -> { dispose(); setupAction.accept(""); }); buttons.addView(setup, cell());
        listen = button(listeningMuted ? "Listen" : "Mute"); listen.setOnClickListener(v -> {
            audioInterrupted = false; listeningMuted = !listeningMuted; styleListen(); applyMute();
        }); buttons.addView(listen, cell());
        talk = button(settings.talkEnabled ? "Talk" : "Talk disabled"); talk.setEnabled(settings.talkEnabled);
        talk.setContentDescription("Tap to start microphone transmission; tap again to stop. Transmission also stops at the configured time limit.");
        talk.setOnClickListener(v -> {
            if (disposed || stopping || !settings.talkEnabled || player == null) return;
            TalkToggle.Action action = talkState.tap();
            if (action == TalkToggle.Action.START) beginTalk();
            else if (action == TalkToggle.Action.STOP) endTalk();
        }); buttons.addView(talk, cell());
        doorLock = button("Door lock"); doorLock.setVisibility(GONE);
        doorLock.setOnClickListener(v -> { if (!disposed && !stopping && !lockBusy && !lockState.isEmpty()) toggleLock.run(); }); buttons.addView(doorLock, cell());
        LayoutParams buttonPosition = new LayoutParams(-2, -2, ControlsLayout.gravity(settings.controls));
        buttonPosition.setMargins(dp(12), dp(12), dp(12), dp(12)); addView(buttons, buttonPosition);
        applyTheme(theme);
        Context app = context.getApplicationContext(); application = app instanceof Application ? (Application) app : null;
        if (application != null) application.registerActivityLifecycleCallbacks(this);
    }
    private ImageButton button(String label) {
        ImageButton b = new ImageButton(getContext()) {
            @Override public boolean performClick() { userActivity(); return super.performClick(); }
        }; b.setTag(label); b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setMinimumWidth(0); b.setMinimumHeight(0); controls.add(b); return b;
    }
    private LinearLayout.LayoutParams cell() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(Math.max(48, settings.controlSize)), dp(Math.max(48, settings.controlSize))); p.setMargins(dp(4), dp(4), dp(4), dp(4)); return p; }
    private void icon(ImageButton button, String name, String label, int fallback) {
        Drawable image = theme.icon(name, "onSurface");
        if (image == null) image = getContext().getDrawable(fallback).mutate();
        image.setTint(theme.color("onSurface")); image.setBounds(0, 0, dp(26), dp(26));
        button.setImageDrawable(image); button.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    }
    private void styleListen() { icon(listen, listeningMuted ? "mdi:volume-off" : "mdi:volume-high", listeningMuted ? "Listen" : "Mute doorbell audio", android.R.drawable.ic_lock_silent_mode_off); }
    void applyTheme(KsTheme next) {
        theme = next;
        for (ImageButton b : controls) {
            b.setBackgroundTintList(null); b.setBackground(ThemeUi.surface(theme, "surfaceContainerHigh", 100));
            if ("Close".equals(b.getTag())) icon(b, "mdi:close", "Close", android.R.drawable.ic_menu_close_clear_cancel);
            if ("Setup".equals(b.getTag())) icon(b, "mdi:cog", "Setup", android.R.drawable.ic_menu_preferences);
        }
        theme.styleText(status, 14, 400, "onSurface"); status.setBackground(theme.card());
        if (statusError) status.setTextColor(theme.color("error"));
        styleListen(); updateTalkButton(); styleLock();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void userActivity() {
        if (idleTimer == null || disposed || stopping) return;
        idleTimer.interaction(android.os.SystemClock.uptimeMillis()); scheduleIdle();
    }
    private void scheduleIdle() {
        removeCallbacks(idleCheck);
        if (disposed || stopping || !isAttachedToWindow()) return;
        long remaining = idleTimer.remaining(android.os.SystemClock.uptimeMillis(), talkState.active() || talkState.stopping());
        if (remaining >= 0) postDelayed(idleCheck, remaining);
    }
    private void checkIdle() {
        if (disposed || stopping) return;
        long remaining = idleTimer.remaining(android.os.SystemClock.uptimeMillis(), talkState.active() || talkState.stopping());
        if (remaining == 0) { dispose(); closeAction.run(); }
        else if (remaining > 0) postDelayed(idleCheck, remaining);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); userActivity(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) userActivity();
        return super.dispatchTouchEvent(event);
    }
    private void showMessage(String text, boolean error) { if (!disposed && error) { statusError = true; status.setText(text); status.setTextColor(theme.color("error")); status.setVisibility(VISIBLE); } }
    private void message(String text, boolean error) { post(() -> showMessage(text, error)); }
    void showError(String text) { message(text, true); }
    void lockStatus(String state, boolean busy) { post(() -> { if (!disposed) { lockState = state; lockBusy = busy; styleLock(); } }); }
    private void colorButton(ImageButton button, boolean red) {
        button.setBackgroundTintList(ColorStateList.valueOf(red ? 0xFFC62828 : 0xFF2E7D32));
        if (button.getDrawable() != null) button.getDrawable().setTint(Color.WHITE);
    }
    private void styleLock() {
        doorLock.setVisibility(!settings.lockEntity.isEmpty() && ("locked".equals(lockState) || "unlocked".equals(lockState)) ? VISIBLE : GONE);
        icon(doorLock, "locked".equals(lockState) ? "mdi:lock" : "mdi:lock-open", "locked".equals(lockState) ? "Locked — tap to unlock" : "Unlocked — tap to lock", android.R.drawable.ic_lock_lock);
        colorButton(doorLock, "unlocked".equals(lockState)); doorLock.setEnabled(!lockBusy && !disposed && !stopping); doorLock.setAlpha(lockBusy ? .55f : 1f);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); post(this::layoutImage); }
    private void layoutImage() {
        if (disposed || getWidth() == 0 || getHeight() == 0) return;
        int w = getWidth(), h = getHeight(); float aspect = Geometry.aspect(settings.viewport);
        if (aspect > 0) { if ((float) w / h > aspect) w = Math.round(h * aspect); else h = Math.round(w / aspect); }
        viewport.setLayoutParams(new LayoutParams(w, h, Gravity.CENTER));
        float[] r = Geometry.image(w, h, videoWidth, videoHeight, settings.sizing, settings.horizontal, settings.vertical);
        LayoutParams p = new LayoutParams(Math.max(1, Math.round(r[2])), Math.max(1, Math.round(r[3])));
        p.leftMargin = Math.round(r[0]); p.topMargin = Math.round(r[1]); p.gravity = Gravity.TOP | Gravity.LEFT; texture.setLayoutParams(p);
    }
    private void beginTalk() {
        if (disposed || stopping || transmitter != null || !settings.talkEnabled || player == null) {
            talkState.finished(); updateTalkButton(); return;
        }
        audioInterrupted = false; updateTalkButton(); applyMute();
        final Talkback[] session = new Talkback[1];
        Talkback next = new Talkback(getContext(), settings, new Talkback.Listener() {
            public boolean destination(String address) {
                return !disposed && !stopping && transmitter == session[0] && addressSave.remember(settings, address);
            }
            public void status(String text, boolean error) { post(() -> {
                if (!disposed && !stopping && transmitter == session[0] && talkState.active()) {
                    if (error) showMessage(text, true);
                }
            }); }
            public void microphoneActive() { post(() -> {
                if (!disposed && !stopping && transmitter == session[0]) { talkState.microphoneActive(); updateTalkButton(); }
            }); }
            public void finished() { post(() -> {
                if (transmitter != session[0]) return;
                transmitter = null; talkState.finished(); removeCallbacks(talkTimeout);
                if (!disposed) { updateTalkButton(); applyMute(); }
            }); }
        });
        session[0] = next; transmitter = next;
        if (stopping) { next.close(); talkState.finished(); transmitter = null; updateTalkButton(); return; }
        postDelayed(talkTimeout, settings.talkLimit * 1000L); next.start();
    }
    private void endTalk() {
        removeCallbacks(talkTimeout); boolean wasTalking = talkState.stop();
        Talkback current = transmitter; if (current != null) current.close();
        else talkState.finished();
        updateTalkButton(); applyMute();
        if (wasTalking && !disposed) message("Talk stopped", false);
    }
    private void updateTalkButton() {
        if (talk == null) return;
        boolean highlighted = talkState.active() || talkState.stopping();
        talk.setBackgroundTintList(null); talk.setBackground(ThemeUi.surface(theme, "surfaceContainerHigh", 100));
        icon(talk, "mdi:microphone", settings.talkEnabled ? talkState.label() : "Talk disabled", android.R.drawable.ic_btn_speak_now);
        if (settings.talkEnabled) colorButton(talk, highlighted);
        talk.setEnabled(settings.talkEnabled && player != null && !talkState.stopping() && !disposed && !stopping);
        talk.setContentDescription(talkState.active() ? "Microphone session enabled. Tap to stop transmitting." : "Tap to start microphone transmission.");
        scheduleIdle();
    }
    private void applyMute() {
        if (player != null) player.setMuted(listeningMuted || talkState.active() && settings.muteWhileTalking);
        if (disposed || stopping || audioInterrupted || player == null) music.close(); else music.update(!listeningMuted, talkState.active() || talkState.stopping());
    }
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (disposed || stopping) return;
        final long token = playbackCallbacks.next();
        StreamPlayer previous = player; player = null; if (previous != null) { previous.close(); endTalk(); }
        StreamPlayer next = new StreamPlayer(settings, new Surface(st), new StreamPlayer.Listener() {
            public void status(String text, boolean error) { postPlayback(token, () -> showMessage(text, error)); }
            public void discovered(List<DiscoveredCamera> cameras, List<String> streams) { playbackCallbacks.run(token, () -> { if (!disposed && !stopping) discovery.accept(cameras, streams); }); }
            public void size(int width, int height) { postPlayback(token, () -> { videoWidth = width; videoHeight = height; layoutImage(); }); }
            public void failed() { stopTalkForPlayback(token); postPlayback(token, () -> { playbackCallbacks.next(); if (player != null) { player.close(); player = null; } endTalk(); music.close(); talk.setEnabled(false); }); }
            public void connectionFailed(String reason) {
                stopTalkForPlayback(token);
                postPlayback(token, () -> { dispose(); setupAction.accept(reason); });
            }
            public void decoderFallback() { stopTalkForPlayback(token); postPlayback(token, () -> endTalk()); }
        });
        player = next; if (stopping) next.close(); applyMute(); next.start(); layoutImage(); updateTalkButton();
    }
    private void postPlayback(long token, Runnable action) { post(() -> { if (!disposed && !stopping && playbackCallbacks.current(token)) action.run(); }); }
    private void stopTalkForPlayback(long token) { playbackCallbacks.run(token, () -> { Talkback current = transmitter; if (current != null) current.close(); }); }
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { playbackCallbacks.next(); if (player != null) { player.close(); player = null; } endTalk(); music.close(); return true; }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus && music != null) { audioInterrupted = true; listeningMuted = true; styleListen(); endTalk(); music.close(); } }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE && status != null && !disposed) { dispose(); closeAction.run(); }
    }
    void dispose() {
        if (disposed) return; disposed = true; stopTransports(); endTalk();
        removeCallbacks(idleCheck);
        if (player != null) { player.close(); player = null; }
        if (application != null) application.unregisterActivityLifecycleCallbacks(this);
        setKeepScreenOn(false);
    }
    // Safe from the plugin worker: do not wait for the UI queue to stop the microphone.
    void stopTransports() {
        stopping = true;
        playbackCallbacks.next();
        music.close();
        Talkback t = transmitter; if (t != null) t.close();
        StreamPlayer p = player; if (p != null) p.close();
    }
    public void onActivityPaused(Activity activity) { stopTransports(); post(() -> { if (!disposed) { dispose(); closeAction.run(); } }); }
    public void onActivityStopped(Activity activity) { onActivityPaused(activity); }
    public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    public void onActivityCreated(Activity activity, Bundle state) {}
    public void onActivityStarted(Activity activity) {}
    public void onActivityResumed(Activity activity) {}
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
