// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.content.Context;
import android.media.*;
import android.os.*;

/** Transient requests only: never writes device/KS volume or music-group state.
 * Other apps may pause instead of ducking. KS players do not take audio focus.
 */
final class MusicFocus implements AutoCloseable {
    private final AudioManager manager;
    private final Runnable lost;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioFocusRequest request;
    private int mode;
    MusicFocus(Context context, Runnable lost) { manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); this.lost = lost; }
    static int mode(boolean listening, boolean talking) { return talking ? 2 : listening ? 1 : 0; }
    synchronized void update(boolean listening, boolean talking) {
        int next = mode(listening, talking);
        if (next == mode) return;
        close(); if (next == 0 || manager == null) return;
        final AudioFocusRequest[] owner = new AudioFocusRequest[1];
        AudioFocusRequest candidate = new AudioFocusRequest.Builder(next == 2 ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0) main.post(() -> { synchronized (MusicFocus.this) { if (request == owner[0] && request != null) { close(); lost.run(); } } });
            }, main).build();
        owner[0] = candidate;
        try { if (manager.requestAudioFocus(candidate) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { request = candidate; mode = next; } }
        catch (RuntimeException ignored) { /* Optional, host/Android policy can deny focus. Never change volume instead. */ }
    }
    public synchronized void close() {
        AudioFocusRequest old = request; request = null; mode = 0;
        if (old != null && manager != null) try { manager.abandonAudioFocusRequest(old); } catch (RuntimeException ignored) {}
    }
}
