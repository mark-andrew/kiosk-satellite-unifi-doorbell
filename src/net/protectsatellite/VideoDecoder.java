// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.media.*;
import android.os.Build;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One worker owns parameter discovery, hardware-first selection and fallback. */
final class VideoDecoder implements AutoCloseable {
    private final Sdp.Track track; private final Surface surface;
    private final StreamPlayer.Listener listener; private final AtomicBoolean closed; private final Runnable rendered;
    private final boolean hevc;
    private final Map<Integer, byte[]> sets = new HashMap<>();
    private MediaCodec codec; private MediaFormat format; private VideoParameters parameters;
    private List<DecoderOrder.Candidate> candidates = Collections.emptyList();
    private int nextCandidate; private long epoch = -1; private boolean started, waitForKey = true;
    VideoDecoder(Sdp.Track track, Surface surface, StreamPlayer.Listener listener, AtomicBoolean closed, Runnable rendered) throws Exception {
        this.track = track; this.surface = surface; this.listener = listener; this.closed = closed; this.rendered = rendered; hevc = "H265".equals(track.codec);
        Sdp.videoMime(track);
        for (String key : hevc ? new String[] {"sprop-vps", "sprop-sps", "sprop-pps"} : new String[] {"sprop-parameter-sets"}) {
            String encoded = track.fmtp.get(key); if (encoded == null) continue;
            if (encoded.length() > 24000) throw new IOException("Video SDP parameters exceed safety limit");
            for (String item : encoded.split(",", -1)) {
                byte[] nal;
                try { nal = android.util.Base64.decode(item, android.util.Base64.DEFAULT); }
                catch (IllegalArgumentException e) { throw new IOException("Invalid video SDP parameter encoding"); }
                remember(nal);
            }
        }
        // Decoder creation waits for input: SDP may omit sets, in which case
        // the first in-band VPS/SPS/PPS supplies the real dimensions/profile.
    }
    private int type(byte[] nal) { return hevc ? (nal[0] & 0x7e) >> 1 : nal[0] & 31; }
    private boolean remember(byte[] nal) throws IOException {
        if (nal.length == 0) throw new IOException("Empty video parameter set");
        int type = type(nal);
        if (!(hevc ? type >= 32 && type <= 34 : type == 7 || type == 8)) return false;
        if (nal.length > 16384) throw new IOException("Video parameter set exceeds safety limit");
        byte[] previous = sets.put(type, nal); return !Arrays.equals(previous, nal);
    }
    private boolean complete() { return hevc ? sets.containsKey(32) && sets.containsKey(33) && sets.containsKey(34) : sets.containsKey(7) && sets.containsKey(8); }
    private void detect() throws Exception {
        parameters = VideoParameters.sps(sets.get(hevc ? 33 : 7), hevc);
        int profile = profile(parameters), level = level(parameters);
        if (profile == 0 || level == 0 || parameters.chroma != 1) throw new IOException("Selected video profile/chroma is not supported by this player");
        format = MediaFormat.createVideoFormat(Sdp.videoMime(track), parameters.width, parameters.height);
        format.setInteger(MediaFormat.KEY_PROFILE, profile); format.setInteger(MediaFormat.KEY_LEVEL, level);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Protocol.MAX_FRAME);
        if (hevc) format.setByteBuffer("csd-0", ByteBuffer.wrap(Protocol.hevcConfiguration(Arrays.asList(sets.get(32), sets.get(33), sets.get(34)))));
        else {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(annex(sets.get(7)))); format.setByteBuffer("csd-1", ByteBuffer.wrap(annex(sets.get(8))));
        }
        List<DecoderOrder.Candidate> available = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(Sdp.videoMime(track));
                boolean supported = caps.isFormatSupported(format);
                boolean software = Build.VERSION.SDK_INT >= 29 ? info.isSoftwareOnly() : DecoderOrder.software(info.getName());
                available.add(new DecoderOrder.Candidate(info.getName(), software, supported));
            } catch (IllegalArgumentException ignored) { /* Other MIME, no usable capabilities. */ }
        }
        candidates = DecoderOrder.choose(available); nextCandidate = 0;
        listener.size(parameters.displayWidth, parameters.displayHeight);
        startNext();
    }
    private static byte[] annex(byte[] nal) { byte[] result = new byte[nal.length + 4]; result[3] = 1; System.arraycopy(nal, 0, result, 4, nal.length); return result; }
    private void startNext() throws Exception {
        releaseCodec();
        while (nextCandidate < candidates.size() && !closed.get()) {
            DecoderOrder.Candidate selected = candidates.get(nextCandidate++);
            try {
                codec = MediaCodec.createByCodecName(selected.name);
                codec.configure(format, surface, null, 0); codec.start(); started = true; waitForKey = true; epoch = -1;
                listener.status(track.codec + " " + parameters.displayWidth + "×" + parameters.displayHeight + " — " + (selected.software ? "software" : "hardware") + " decoder", false);
                return;
            } catch (Exception ignored) { releaseCodec(); }
        }
        if (!closed.get()) throw new IOException("No working hardware or software decoder for " + track.codec + " " + parameters.width + "x" + parameters.height + " (profile " + parameters.profile + ", " + parameters.bitDepth + "-bit). Try medium or low quality");
    }
    void input(byte[] data, long timestamp) throws Exception {
        if (closed.get()) return;
        boolean changed = false, key = false;
        for (byte[] nal : VideoParameters.annexB(data)) {
            changed |= remember(nal); int t = type(nal);
            key |= hevc ? t >= 16 && t <= 23 : t == 5;
        }
        if (changed && parameters != null) { releaseCodec(); parameters = null; }
        if (parameters == null) {
            if (!complete()) return;
            detect();
        }
        if (closed.get() || codec == null || waitForKey && !key) return;
        for (;;) {
            try {
                drain(); int index = codec.dequeueInputBuffer(10000); if (index < 0) return;
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || data.length > buffer.capacity()) throw new IOException("Encoded frame exceeds decoder input capacity");
                buffer.clear(); buffer.put(data); if (epoch < 0) epoch = timestamp;
                codec.queueInputBuffer(index, 0, data.length, ((timestamp - epoch) & 0xffffffffL) * 1000000L / track.rate, 0);
                waitForKey = false; drain(); return;
            } catch (IllegalStateException e) {
                // Android codec runtime failures are bounded: try each remaining
                // compatible implementation once, retaining no unbounded frames.
                listener.decoderFallback(); startNext(); if (closed.get() || codec == null || !key) return;
            }
        }
    }
    boolean fallbackAfterStall() throws Exception {
        if (parameters == null || nextCandidate >= candidates.size() || closed.get()) return false;
        startNext(); return codec != null;
    }
    private void drain() throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (;;) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat actual = codec.getOutputFormat();
                int width = actual.getInteger(MediaFormat.KEY_WIDTH), height = actual.getInteger(MediaFormat.KEY_HEIGHT);
                if (actual.containsKey("crop-right")) width = actual.getInteger("crop-right") - actual.getInteger("crop-left") + 1;
                if (actual.containsKey("crop-bottom")) height = actual.getInteger("crop-bottom") - actual.getInteger("crop-top") + 1;
                listener.size(width, height);
            } else if (index >= 0) {
                try { if (!closed.get()) rendered.run(); } finally { codec.releaseOutputBuffer(index, !closed.get()); }
            }
        }
    }
    private static int profile(VideoParameters p) {
        if (p.hevc) return p.profile == 1 && p.bitDepth == 8 ? 1 : p.profile == 2 && p.bitDepth <= 10 ? 2 : 0;
        int[] ids = {66, 77, 88, 100, 110, 122, 244};
        for (int i = 0; i < ids.length; i++) if (ids[i] == p.profile) return 1 << i;
        return 0;
    }
    private static int level(VideoParameters p) {
        int[] levels = p.hevc ? new int[] {30, 60, 63, 90, 93, 120, 123, 150, 153, 156, 180, 183, 186} : new int[] {10, 9, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62};
        for (int i = 0; i < levels.length; i++) if (levels[i] == p.level) return 1 << (p.hevc ? i * 2 + (p.highTier ? 1 : 0) : i);
        return 0;
    }
    private void releaseCodec() {
        if (codec == null) return;
        try { if (started) codec.stop(); } catch (RuntimeException ignored) {}
        try { codec.release(); } catch (RuntimeException ignored) {}
        codec = null; started = false;
    }
    public void close() { releaseCodec(); }
}
