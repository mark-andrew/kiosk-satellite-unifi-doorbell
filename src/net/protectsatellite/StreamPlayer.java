// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.media.*;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** RTSPS/TCP receiver. Android MediaCodec decodes H264/H265 and AAC; no external service. */
final class StreamPlayer implements AutoCloseable {
    interface Listener { void status(String message, boolean error); void size(int width, int height); void failed(); default void discovered(List<DiscoveredCamera> cameras, List<String> streams) {} default void decoderFallback() {} default void connectionFailed(String reason) { failed(); } }
    private final Settings settings;
    private final Surface surface;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile NetworkCleanup.Task socketCleanup;
    private volatile ProtectApi api;
    private volatile boolean muted;
    private InputStream in; private OutputStream out; private int cseq;
    private String session = "";
    private final Map<Integer, Decoder> decoders = new HashMap<>();
    private final Protocol.H264 h264 = new Protocol.H264();
    private final Protocol.H265 h265 = new Protocol.H265();
    private long lastVideoFrame;
    StreamPlayer(Settings settings, Surface surface, Listener listener) {
        this.settings = settings; this.surface = surface; this.listener = listener; muted = settings.startMuted;
    }
    void start() { Thread t = new Thread(this::run, "protect-stream"); t.setDaemon(true); t.start(); }
    void setMuted(boolean value) { muted = value; }
    private void run() {
        boolean connecting = true;
        try {
            settings.requireCamera();
            ProtectApi connection = new ProtectApi(settings); api = connection;
            if (closed.get()) return;
            List<DiscoveredCamera> cameras = DiscoveredCamera.parse(connection.cameras()); boolean found = false;
            for (DiscoveredCamera camera : cameras) if (camera.id.equals(settings.cameraId)) found = true;
            if (!found) throw new IOException("Selected UniFi doorbell is no longer available; choose a discovered doorbell in Setup");
            org.json.JSONObject streams = connection.streams(settings.cameraId);
            if (closed.get()) return;
            listener.discovered(cameras, StreamChoices.enabled(streams, settings));
            URI url = ProtectApi.streamUrl(streams, settings);
            if (closed.get()) return;
            Socket connected = connection.tls.rtsp(url);
            socketCleanup = new NetworkCleanup.Task(connected::close);
            if (closed.get()) return;
            in = new BufferedInputStream(connected.getInputStream()); out = connected.getOutputStream();
            Protocol.Message description = request("DESCRIBE", url, "Accept: application/sdp\r\n");
            connecting = false; // SDP/codec errors are media errors, not connection failures.
            List<Sdp.Track> tracks = Sdp.parse(new String(description.body, "UTF-8"));
            URI base = url;
            if (description.headers.containsKey("content-base")) base = Sdp.control(url, description.headers.get("content-base"));
            boolean hasVideo = false, hasAudio = false; int channel = 0; String videoLabel = "";
            for (Sdp.Track track : tracks) {
                boolean video = "video".equals(track.kind) && !hasVideo;
                boolean audio = "audio".equals(track.kind) && !hasAudio;
                if (!video && !audio) continue;
                if (video) { Sdp.videoMime(track); videoLabel = track.codec; }
                if (audio && !"MPEG4-GENERIC".equals(track.codec)) {
                    listener.status("Video only: this stream's listening codec is not yet supported", false); continue;
                }
                Decoder decoder = new Decoder(track, video);
                decoders.put(channel, decoder);
                connecting = true;
                Protocol.Message setup = request("SETUP", Sdp.control(base, track.control), "Transport: RTP/AVP/TCP;unicast;interleaved=" + channel + "-" + (channel + 1) + "\r\n");
                String transport = setup.headers.getOrDefault("transport", "");
                if (!transport.toLowerCase(Locale.ROOT).contains("interleaved=" + channel + "-" + (channel + 1)))
                    throw new IOException("Protect did not accept requested interleaved RTP channels");
                String returnedSession = setup.headers.getOrDefault("session", "").split(";")[0];
                if (returnedSession.isEmpty() || returnedSession.contains("\r") || returnedSession.contains("\n")) throw new IOException("Missing RTSP session");
                if (!session.isEmpty() && !session.equals(returnedSession)) throw new IOException("RTSP session changed during setup");
                session = returnedSession;
                hasVideo |= video; hasAudio |= audio; channel += 2;
                connecting = false;
            }
            if (!hasVideo) throw new IOException("No compatible video track found");
            connecting = true; request("PLAY", base, "Range: npt=0.000-\r\n"); connecting = false;
            listener.status("Connected — " + videoLabel + " video" + (hasAudio ? " / AAC listening" : " (listening unavailable)"), false);
            long keepalive = System.nanoTime(); lastVideoFrame = keepalive;
            while (!closed.get()) {
                Protocol.Message message = Protocol.read(in);
                if (message.channel >= 0) packet(message);
                else if (message.status < 200 || message.status >= 300) throw new IOException("RTSP keepalive rejected");
                long now = System.nanoTime();
                if (now - keepalive > 15_000_000_000L) { send("GET_PARAMETER", base, ""); keepalive = now; }
                if (now - lastVideoFrame > 10_000_000_000L) {
                    boolean fallback = false;
                    for (Decoder decoder : decoders.values()) if (decoder.video && decoder.videoDecoder != null) fallback = decoder.videoDecoder.fallbackAfterStall();
                    if (!fallback) throw new IOException("Video decoder stalled or stream parameters/keyframe missing; try medium or low quality");
                    lastVideoFrame = now; listener.decoderFallback();
                }
            }
        } catch (Exception e) {
            if (!closed.get()) {
                String reason = Failures.describe(e, "Stream failed; verify network, TLS and selected codec");
                listener.status(reason, true);
                if (FailureRouting.setup(e, connecting)) listener.connectionFailed(reason);
                else listener.failed();
            }
        } finally {
            close();
            for (Decoder decoder : decoders.values()) decoder.close(); decoders.clear();
            try { surface.release(); } catch (RuntimeException ignored) {}
        }
    }
    private void send(String method, URI uri, String extra) throws IOException {
        if (closed.get()) throw new IOException("Stream closed");
        String target = uri.toASCIIString();
        if (target.contains("\r") || target.contains("\n")) throw new IOException("Invalid RTSP URI");
        String message = method + " " + target + " RTSP/1.0\r\nCSeq: " + (++cseq) + "\r\nUser-Agent: ProtectSatellite/0.1\r\n"
            + (session.isEmpty() ? "" : "Session: " + session + "\r\n") + extra + "\r\n";
        out.write(message.getBytes("UTF-8")); out.flush();
    }
    private Protocol.Message request(String method, URI uri, String extra) throws Exception {
        send(method, uri, extra);
        for (int i = 0; i < 1024; i++) {
            Protocol.Message m = Protocol.read(in);
            if (m.channel >= 0) continue;
            if (!String.valueOf(cseq).equals(m.headers.get("cseq"))) throw new IOException("RTSP response sequence mismatch");
            if (m.status < 200 || m.status >= 300) throw new IOException("RTSP request rejected (status " + m.status + ")"); return m;
        } throw new IOException("RTSP response not received");
    }
    private void packet(Protocol.Message message) throws Exception {
        Decoder decoder = decoders.get(message.channel); if (decoder == null) return;
        Protocol.Rtp p = new Protocol.Rtp(message.body);
        if (p.payloadType != decoder.track.payload) return;
        if (decoder.video) {
            byte[] frame = "H265".equals(decoder.track.codec) ? h265.accept(p) : h264.accept(p);
            if (frame != null) decoder.input(frame, p.timestamp);
        } else {
            // RFC 3640 AAC-hbr with 13-bit size and 3-bit index; no fragmentation.
            if (p.length < 4) throw new IOException("Truncated AAC access-unit header");
            int bits = Protocol.u16(p.data, p.offset);
            if (bits == 0 || bits % 16 != 0 || bits > 4096) throw new IOException("Unsupported AAC RTP header layout");
            int count = bits / 16, at = p.offset + 2 + bits / 8;
            if (at > p.offset + p.length) throw new IOException("Truncated AAC header block");
            for (int i = 0; i < count; i++) {
                int size = Protocol.u16(p.data, p.offset + 2 + i * 2) >>> 3;
                if (size == 0 || at + size > p.offset + p.length) throw new IOException("Fragmented AAC audio is not supported");
                decoder.input(Arrays.copyOfRange(p.data, at, at + size), (p.timestamp + i * 1024L) & 0xffffffffL); at += size;
            }
            if (at != p.offset + p.length) throw new IOException("Unsupported AAC packet trailer");
        }
    }
    public void close() {
        closed.set(true);
        ProtectApi a = api; if (a != null) a.close();
        NetworkCleanup.Task task = socketCleanup; if (task != null) task.close();
    }
    private final class Decoder implements AutoCloseable {
        final Sdp.Track track; final boolean video;
        MediaCodec codec; AudioTrack audio; VideoDecoder videoDecoder;
        long epoch = -1; boolean started;
        Decoder(Sdp.Track track, boolean video) throws Exception {
            this.track = track; this.video = video;
            try {
                MediaFormat format;
                if (video) {
                    videoDecoder = new VideoDecoder(track, surface, listener, closed, () -> lastVideoFrame = System.nanoTime());
                    return;
                } else {
                    if (track.channels < 1 || track.channels > 2 || track.rate < 8000 || track.rate > 96000
                            || !"13".equals(track.fmtp.get("sizelength")) || !"3".equals(track.fmtp.get("indexlength"))
                            || !"3".equals(track.fmtp.get("indexdeltalength"))) throw new IOException("Unsupported AAC stream parameters");
                    byte[] config = Sdp.hex(track.fmtp.get("config"));
                    if ((config[0] & 255) >>> 3 != 2) throw new IOException("Listening requires AAC-LC audio");
                    format = MediaFormat.createAudioFormat("audio/mp4a-latm", track.rate, track.channels);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(config)); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
                }
                codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
                codec.configure(format, null, null, 0); codec.start(); started = true;
            } catch (Exception e) { close(); throw e; }
        }
        void input(byte[] data, long timestamp) throws Exception {
            if (closed.get()) return;
            if (video) { videoDecoder.input(data, timestamp); return; }
            drain();
            int index = codec.dequeueInputBuffer(10000);
            if (index < 0) return; // Drop rather than build latency without bounds.
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || data.length > buffer.capacity()) throw new IOException("Encoded frame exceeds decoder input capacity");
            buffer.clear(); buffer.put(data);
            if (epoch < 0) epoch = timestamp;
            codec.queueInputBuffer(index, 0, data.length, ((timestamp - epoch) & 0xffffffffL) * 1000000L / track.rate, 0);
            drain();
        }
        void drain() throws Exception {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (;;) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    if (video) {
                        int width = format.getInteger(MediaFormat.KEY_WIDTH), height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        if (format.containsKey("crop-right")) width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                        if (format.containsKey("crop-bottom")) height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                        listener.size(width, height);
                    } else {
                        if (audio != null) { audio.release(); audio = null; }
                        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (count != 1 && count != 2) throw new IOException("Unsupported decoded audio channel count");
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                            throw new IOException("Unsupported decoded PCM format");
                        int mask = count == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                        int minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
                        if (minimum <= 0) throw new IOException("Audio output configuration unavailable");
                        audio = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(minimum, rate * count / 5)).setTransferMode(AudioTrack.MODE_STREAM).build(); audio.play();
                    }
                } else if (index >= 0) {
                    try {
                        if (video) lastVideoFrame = System.nanoTime();
                        else if (audio != null && info.size > 0) {
                            audio.setVolume(muted ? 0 : 1);
                            ByteBuffer pcm = codec.getOutputBuffer(index);
                            if (pcm != null) { pcm.position(info.offset); pcm.limit(info.offset + info.size); audio.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING); }
                        }
                    } finally { codec.releaseOutputBuffer(index, video && !closed.get()); }
                }
            }
        }
        public void close() {
            if (videoDecoder != null) { videoDecoder.close(); videoDecoder = null; }
            if (codec != null) {
                try { if (started) codec.stop(); } catch (RuntimeException ignored) {}
                try { codec.release(); } catch (RuntimeException ignored) {} codec = null;
            }
            if (audio != null) {
                try { audio.pause(); audio.flush(); } catch (RuntimeException ignored) {}
                try { audio.release(); } catch (RuntimeException ignored) {} audio = null;
            }
        }
    }
}
