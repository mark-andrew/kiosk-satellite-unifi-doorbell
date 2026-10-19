// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One explicitly toggled talk session. Never records or buffers speech to disk. */
final class Talkback implements AutoCloseable {
    interface Listener { void status(String text, boolean error); boolean destination(String address); void microphoneActive(); void finished(); }
    private final Context context; private final Settings settings; private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object gate = new Object();
    private final long deadline;
    private volatile ProtectApi api;
    private volatile NetworkCleanup.Task socketCleanup;
    private volatile AudioRecord recorder;
    private MediaCodec encoder; private boolean encoderStarted;
    Talkback(Context context, Settings settings, Listener listener) {
        this.context = context; this.settings = settings; this.listener = listener;
        deadline = System.nanoTime() + settings.talkLimit * 1_000_000_000L;
    }
    void start() { Thread t = new Thread(this::run, "protect-talk"); t.setDaemon(true); t.start(); }
    private boolean active() { return !closed.get() && System.nanoTime() < deadline; }
    private void run() {
        try {
            if (!settings.talkEnabled) throw new IOException("Two-way audio is disabled in plugin settings");
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException();
            if (!active()) return;
            listener.status("Opening talk session — tap Talk again to cancel", false);
            ProtectApi connection = new ProtectApi(settings); api = connection;
            if (!active()) return;
            JSONObject camera = connection.camera();
            JSONObject flags = camera.optJSONObject("featureFlags");
            if (flags == null || !flags.optBoolean("hasSpeaker", false)) throw new IOException("Protect does not report a speaker for this doorbell");
            if (!active()) return;
            TalkSession session = new TalkSession(connection.talkback());
            if (!active()) return;
            InetAddress address = session.authenticatedDestination();
            if (!active()) return;
            if (!listener.destination(address.getHostAddress())) throw new IOException("Talkback settings changed or destination could not be saved; tap Talk to retry");
            if (!active()) return;
            DatagramSocket udp = new DatagramSocket(); socketCleanup = new NetworkCleanup.Task(udp::close);
            if (!active()) return;
            udp.connect(address, session.port);
            String name = null; int rate = session.rate; MediaFormat format = null;
            for (int candidate : session.encoderRates()) {
                MediaFormat attempt = MediaFormat.createAudioFormat(session.mime(), candidate, 1);
                attempt.setInteger(MediaFormat.KEY_BIT_RATE, session.opus ? 32000 : 64000);
                attempt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(4096, candidate / 10));
                if (!session.opus) attempt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                String available = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(attempt);
                if (available != null) { name = available; rate = candidate; format = attempt; break; }
            }
            if (name == null) throw new IOException("This satellite has no encoder for the negotiated talkback codec and sampling rate");
            encoder = MediaCodec.createByCodecName(name); encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start(); encoderStarted = true;
            int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Microphone does not support the negotiated sample rate");
            AudioRecord capture = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setSampleRate(rate).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, rate / 5)).build();
            synchronized (gate) {
                recorder = capture;
                if (!active()) return;
                if (capture.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
                capture.startRecording();
            }
            if (capture.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone is unavailable or in use");
            listener.microphoneActive();
            listener.status("Microphone active — preparing audio", false);
            int sequence = new SecureRandom().nextInt(65536), ssrc = new SecureRandom().nextInt();
            long samples = 0; boolean sent = false;
            byte[] pcm = new byte[Math.max(320, rate / 50 * 2)]; // 20 ms mono PCM.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (active()) {
                int index = encoder.dequeueInputBuffer(10000);
                if (index >= 0) {
                    ByteBuffer buffer = encoder.getInputBuffer(index);
                    if (buffer == null) throw new IOException("Encoder input unavailable");
                    int max = Math.min(pcm.length, buffer.capacity()) & ~1;
                    int n = capture.read(pcm, 0, max, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) throw new IOException("Microphone capture failed; talkback stopped");
                    buffer.clear(); if (n > 0) buffer.put(pcm, 0, n);
                    encoder.queueInputBuffer(index, 0, n, samples * 1000000L / rate, 0); samples += n / 2;
                    if (n == 0) Thread.sleep(5);
                }
                for (;;) {
                    int output = encoder.dequeueOutputBuffer(info, 0);
                    if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (output < 0) continue;
                    try {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size == 0) continue;
                        ByteBuffer data = encoder.getOutputBuffer(output);
                        if (data == null || info.size > 16000) throw new IOException("Unexpected talkback encoder output");
                        data.position(info.offset); data.limit(info.offset + info.size);
                        byte[] encoded = new byte[info.size]; data.get(encoded);
                        byte[] packet = session.packet(encoded, sequence++, Math.max(0, info.presentationTimeUs), ssrc, rate);
                        if (packet.length > 1400) throw new IOException("Talkback packet exceeds safe datagram size");
                        // Stop and send use the same gate: no audio send can begin after close returns.
                        synchronized (gate) {
                            if (!active()) return;
                            udp.send(new DatagramPacket(packet, packet.length));
                        }
                        if (!sent) { sent = true; listener.status("Sending microphone audio — " + (session.opus ? "Opus" : "AAC") + " — tap Talk to stop", false); }
                    } finally { encoder.releaseOutputBuffer(output, false); }
                }
            }
            if (!closed.get()) listener.status("Talk time limit reached", false);
        } catch (Exception e) {
            if (!closed.get()) listener.status(Failures.describe(e, "Talkback failed; verify permissions, codec support and connectivity"), true);
        } finally {
            close();
            synchronized (gate) {
                AudioRecord r = recorder; recorder = null;
                if (r != null) { try { r.release(); } catch (RuntimeException ignored) {} }
            }
            if (encoder != null) {
                try { if (encoderStarted) encoder.stop(); } catch (RuntimeException ignored) {}
                try { encoder.release(); } catch (RuntimeException ignored) {} encoder = null;
            }
            listener.finished();
        }
    }
    public void close() {
        synchronized (gate) {
            closed.set(true);
            NetworkCleanup.Task s = socketCleanup; if (s != null) s.close();
            AudioRecord r = recorder; if (r != null) try { r.stop(); } catch (RuntimeException ignored) {}
        }
        ProtectApi a = api; if (a != null) a.close();
    }
}
