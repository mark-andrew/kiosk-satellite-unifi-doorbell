// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import org.json.*;

public final class CoreTest {
    private static int passed;
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; }
    static void rejects(Checked action, String name) throws Exception {
        try { action.run(); } catch (Exception e) { passed++; return; } throw new AssertionError("Accepted " + name);
    }
    static void near(float expected, float actual, String name) { check(Math.abs(expected - actual) < 0.01f, name); }
    public static void main(String[] args) throws Exception {
        Map<String, Object> m = new HashMap<>();
        Settings defaults = new Settings(m);
        check(!defaults.talkEnabled && defaults.startMuted, "Safe defaults");
        check(defaults.viewport.equals("Auto"), "Adaptive viewport default");
        rejects(defaults::requireConnection, "empty connection");
        for (String bad : new String[] {"http://console", "https://u:p@console", "https://console/path", "https://console?key=x", "https://console#x", "file:///etc/passwd"}) {
            m.put("console", bad); rejects(() -> new Settings(m), "unsafe console");
        }
        m.clear(); m.put("console", "https://console.example:7443"); m.put("apiKey", "test-only-key");
        new Settings(m).requireConnection(); passed++;
        rejects(() -> new Settings(m).requireCamera(), "empty camera");
        m.put("cameraId", "../../other"); rejects(() -> new Settings(m), "camera path injection"); m.remove("cameraId");
        m.put("certificatePin", "00"); rejects(() -> new Settings(m), "invalid certificate pin"); m.remove("certificatePin");
        m.put("horizontal", Double.NaN); rejects(() -> new Settings(m), "NaN crop"); m.remove("horizontal");
        m.put("vertical", 101); rejects(() -> new Settings(m), "out of range crop"); m.remove("vertical");
        m.put("talkLimit", 1000); rejects(() -> new Settings(m), "unbounded talk"); m.remove("talkLimit");
        m.put("sizing", "magic"); rejects(() -> new Settings(m), "unknown sizing"); m.remove("sizing");
        m.put("apiKey", "key\r\nHeader: x"); rejects(() -> new Settings(m), "header injection"); m.clear();

        float[] r = Geometry.image(1280, 800, 640, 480, "Fill", .5f, .5f);
        near(1280, r[2], "16:10 fill width"); near(960, r[3], "16:10 fill height"); near(-80, r[1], "16:10 crop");
        r = Geometry.image(1920, 1080, 640, 480, "Fill", .5f, .5f);
        near(1440, r[3], "16:9 fill height"); near(-180, r[1], "16:9 crop");
        r = Geometry.image(1280, 800, 640, 480, "Fit", .5f, .5f);
        near(1066.6666f, r[2], "Fit width"); near(106.6666f, r[0], "Fit bars");
        r = Geometry.image(1280, 800, 640, 480, "Stretch", .5f, .5f); near(800, r[3], "Stretch height");
        r = Geometry.image(1280, 800, 640, 480, "Fill", 0, 1); near(-160, r[1], "Bottom crop anchor");
        near(0, Geometry.aspect("Auto"), "Auto aspect"); near(1.6f, Geometry.aspect("16:10"), "Explicit aspect");

        Protocol.Message reply = Protocol.read(new ByteArrayInputStream("RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 3\r\n\r\nabc".getBytes("UTF-8")));
        check(reply.status == 200 && reply.headers.get("cseq").equals("3") && reply.body.length == 3, "RTSP response");
        reply = Protocol.read(new ByteArrayInputStream(new byte[] {'$', 2, 0, 2, 1, 2})); check(reply.channel == 2 && reply.body.length == 2, "Interleaved frame");
        rejects(() -> Protocol.read(new ByteArrayInputStream("RTSP/1.0 200 OK\r\nContent-Length: 999999999\r\n\r\n".getBytes("UTF-8"))), "oversized RTSP");
        rejects(() -> Protocol.read(new ByteArrayInputStream(new byte[] {'$', 0, 0, 3, 1})), "truncated RTP");
        rejects(() -> Protocol.read(new ByteArrayInputStream(new byte[] {})), "EOF");
        byte[] packet = Protocol.rtp(new byte[] {0x65, 1, 2}, 65535, 0xfedcba98L, 1234, 97);
        Protocol.Rtp parsed = new Protocol.Rtp(packet);
        check(parsed.sequence == 65535 && parsed.timestamp == 0xfedcba98L && parsed.payloadType == 97 && parsed.length == 3, "RTP round trip");
        rejects(() -> new Protocol.Rtp(new byte[11]), "short RTP");
        byte[] badPadding = packet.clone(); badPadding[0] |= 0x20; badPadding[badPadding.length - 1] = 100;
        rejects(() -> new Protocol.Rtp(badPadding), "RTP padding overrun");
        byte[] badExtension = packet.clone(); badExtension[0] |= 0x10;
        rejects(() -> new Protocol.Rtp(badExtension), "truncated RTP extension");
        Protocol.H264 depacketizer = new Protocol.H264(); packet[1] |= (byte) 128;
        byte[] frame = depacketizer.accept(new Protocol.Rtp(packet));
        check(Arrays.equals(frame, new byte[] {0, 0, 0, 1, 0x65, 1, 2}), "H264 single NAL");
        byte[] start = Protocol.rtp(new byte[] {0x7c, (byte) 0x85, 1, 2}, 0, 9, 0, 96);
        byte[] end = Protocol.rtp(new byte[] {0x7c, 0x45, 3, 4}, 1, 9, 0, 96); end[1] |= (byte) 128;
        check(depacketizer.accept(new Protocol.Rtp(start)) == null, "H264 FU start waits");
        check(Arrays.equals(depacketizer.accept(new Protocol.Rtp(end)), new byte[] {0, 0, 0, 1, 0x65, 1, 2, 3, 4}), "H264 FU reassembly and sequence wrap");
        Protocol.H264 loss = new Protocol.H264(); loss.accept(new Protocol.Rtp(start)); end[3] = 2;
        check(loss.accept(new Protocol.Rtp(end)) == null, "Drop fragmented frame on packet loss");
        byte[] aggregation = Protocol.rtp(new byte[] {24, 0, 2, 0x67, 1, 0, 2, 0x68, 2}, 4, 10, 0, 96); aggregation[1] |= (byte) 128;
        check(new Protocol.H264().accept(new Protocol.Rtp(aggregation)).length == 12, "H264 aggregation");
        byte[] malformed = Protocol.rtp(new byte[] {24, 0, 100, 1}, 5, 11, 0, 96);
        rejects(() -> new Protocol.H264().accept(new Protocol.Rtp(malformed)), "invalid H264 aggregation");
        byte[] adts = Protocol.adts(new byte[] {1, 2, 3}, 16000, 1);
        check(adts.length == 10 && (adts[0] & 255) == 255 && (adts[1] & 255) == 241, "AAC ADTS header");
        check((((adts[3] & 3) << 11) | ((adts[4] & 255) << 3) | ((adts[5] & 255) >>> 5)) == 10, "AAC ADTS length");
        rejects(() -> Protocol.adts(new byte[9000], 16000, 1), "oversize AAC");
        rejects(() -> Protocol.adts(new byte[1], 12345, 1), "unsupported AAC sample rate");

        String sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z2Q=,aA==\r\na=control:trackID=0\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000/1\r\na=fmtp:97 config=1408; sizeLength=13; indexLength=3; indexDeltaLength=3\r\na=control:trackID=1\r\n";
        List<Sdp.Track> tracks = Sdp.parse(sdp);
        check(tracks.size() == 2 && tracks.get(0).rate == 90000 && tracks.get(1).fmtp.get("sizelength").equals("13"), "SDP tracks and fmtp");
        URI base = URI.create("rtsps://console:7441/token");
        check(Sdp.control(base, "trackID=0").toString().equals("rtsps://console:7441/token/trackID=0"), "Relative RTSP control");
        rejects(() -> Sdp.control(base, "rtsps://attacker:7441/token"), "foreign control host");
        rejects(() -> Sdp.control(base, "rtsps://console:9999/token"), "foreign control port");
        rejects(() -> Sdp.parse("m=video 0 RTP/SAVP 96\na=control:track\n"), "SRTP not silently treated as RTP");
        rejects(() -> Sdp.parse("m=video 0 RTP/AVP 96\na=control:track\n"), "missing RTP clock");
        rejects(() -> Sdp.hex("xy"), "bad hex"); check(Sdp.hex("1408")[0] == 20, "Codec hex");
        check(!Failures.describe(new IOException("rtsps://secret-token"), "safe fallback").contains("secret-token"), "Exception redaction");
        check(DisplayText.singleLine("Bad\r\nerror", 80).equals("Bad  error"), "Display labels have no control characters");
        check(DisplayText.singleLine(String.join("", Collections.nCopies(100, "x")), 80).length() == 80, "Display labels fit the host limit");
        check(DisplayText.limit("short", 10).equals("short") && DisplayText.limit("123456789", 5).equals("1234…"), "Display text is bounded without changing short messages");
        streamValidationTests();
        ShutdownAudioTest.run();
        TalkToggleTest.run();
        CallbackScopeTest.run();
        VideoParametersTest.run();
        SetupTest.unit();
        IdleTimeoutTest.run();
        RemoteSetupTest.unit();
        DoorbellTest.run();
        HaLocksTest.run();
        AddressRoutingTest.run();
        h265Tests();
        videoTlsTests(args[1]);
        tlsTests(args[0]);
        System.out.println("PASS: " + passed + " offline assertions (including local HTTPS API/TLS tests). No Protect connection made.");
    }
    static Protocol.Rtp hevcPacket(byte[] data, int sequence, long timestamp, boolean marker) throws Exception {
        byte[] packet = Protocol.rtp(data, sequence, timestamp, 1234, 98);
        if (marker) packet[1] |= (byte) 128;
        return new Protocol.Rtp(packet);
    }
    static void h265Tests() throws Exception {
        byte[] single = new Protocol.H265().accept(hevcPacket(new byte[] {0x26, 1, 10, 11}, 1, 9000, true));
        check(Arrays.equals(single, new byte[] {0, 0, 0, 1, 0x26, 1, 10, 11}), "H265 single NAL Annex B");
        Protocol.H265 multiple = new Protocol.H265();
        check(multiple.accept(hevcPacket(new byte[] {0x40, 1, 10}, 1, 9000, false)) == null, "H265 waits for access-unit marker");
        byte[] combined = multiple.accept(hevcPacket(new byte[] {0x26, 1, 20}, 2, 9000, true));
        check(Arrays.equals(combined, new byte[] {0, 0, 0, 1, 0x40, 1, 10, 0, 0, 0, 1, 0x26, 1, 20}), "H265 multiple NAL units per access unit");
        byte[] ap = new byte[] {0x60, 1, 0, 3, 0x40, 1, 10, 0, 3, 0x42, 1, 20};
        check(Arrays.equals(new Protocol.H265().accept(hevcPacket(ap, 3, 10000, true)),
            new byte[] {0, 0, 0, 1, 0x40, 1, 10, 0, 0, 0, 1, 0x42, 1, 20}), "H265 AP splits aggregated NAL units");
        Protocol.H265 fu = new Protocol.H265();
        check(fu.accept(hevcPacket(new byte[] {0x62, 1, (byte) 0x93, 10, 11}, 65535, 11000, false)) == null, "H265 FU start");
        check(fu.accept(hevcPacket(new byte[] {0x62, 1, 0x13, 12}, 0, 11000, false)) == null, "H265 FU middle and sequence wrap");
        check(Arrays.equals(fu.accept(hevcPacket(new byte[] {0x62, 1, 0x53, 13}, 1, 11000, true)),
            new byte[] {0, 0, 0, 1, 0x26, 1, 10, 11, 12, 13}), "H265 FU reconstructs original two-byte header");
        Protocol.H265 layer = new Protocol.H265();
        layer.accept(hevcPacket(new byte[] {0x63, 0x19, (byte) 0x93, 1}, 1, 12000, false));
        check(Arrays.equals(layer.accept(hevcPacket(new byte[] {0x63, 0x19, 0x53, 2}, 2, 12000, true)),
            new byte[] {0, 0, 0, 1, 0x27, 0x19, 1, 2}), "H265 FU preserves layer and temporal ID bits");
        Protocol.H265 gap = new Protocol.H265();
        gap.accept(hevcPacket(new byte[] {0x62, 1, (byte) 0x93, 1}, 5, 13000, false));
        check(gap.accept(hevcPacket(new byte[] {0x62, 1, 0x53, 2}, 7, 13000, true)) == null, "H265 drops fragmented access unit after loss");
        check(gap.accept(hevcPacket(new byte[] {0x26, 1, 3}, 8, 13000, true)) == null, "H265 suppresses rest of damaged timestamp");
        check(gap.accept(hevcPacket(new byte[] {0x26, 1, 4}, 9, 14000, true)) != null, "H265 recovers at next timestamp");
        Protocol.H265 orphan = new Protocol.H265();
        check(orphan.accept(hevcPacket(new byte[] {0x62, 1, 0x53, 2}, 10, 15000, true)) == null, "H265 orphan continuation is discarded");
        Protocol.H265 premature = new Protocol.H265();
        check(premature.accept(hevcPacket(new byte[] {0x62, 1, (byte) 0x93, 1}, 1, 16000, true)) == null, "H265 marker cannot finish an incomplete FU");
        Protocol.H265 changedTime = new Protocol.H265();
        changedTime.accept(hevcPacket(new byte[] {0x26, 1, 1}, 1, 17000, false));
        check(Arrays.equals(changedTime.accept(hevcPacket(new byte[] {0x26, 1, 2}, 2, 18000, true)),
            new byte[] {0, 0, 0, 1, 0x26, 1, 2}), "H265 discards unmarked previous timestamp");
        byte[][] malformed = {
            {0x26}, {0x26, 0}, {(byte) 0xa6, 1}, // short header, invalid tid, forbidden bit
            {0x60, 1}, {0x60, 1, 0}, {0x60, 1, 0, 10, 0x40, 1}, // AP framing
            {0x60, 1, 0, 1, 0x40}, {0x60, 1, 0, 2, 0x40, 1}, // short NAL/single AP
            {0x60, 1, 0, 2, 0x60, 1, 0, 2, 0x40, 1}, // nested AP
            {0x62, 1, (byte) 0x93}, {0x62, 1, (byte) 0xd3, 1}, // empty FU/start+end
            {0x62, 1, (byte) 0xb0, 1}, {0x64, 1, 0} // invalid FU type/PACI
        };
        for (byte[] bad : malformed) rejects(() -> new Protocol.H265().accept(hevcPacket(bad, 1, 19000, true)), "malformed H265 RTP payload");
        Protocol.H265 changedHeader = new Protocol.H265();
        changedHeader.accept(hevcPacket(new byte[] {0x62, 1, (byte) 0x93, 1}, 1, 20000, false));
        rejects(() -> changedHeader.accept(hevcPacket(new byte[] {0x62, 1, 0x54, 2}, 2, 20000, true)), "changed FU NAL type");
        Protocol.H265 oversized = new Protocol.H265(); byte[] large = new byte[65000]; large[0] = 0x26; large[1] = 1;
        rejects(() -> { for (int i = 0; i < 40; i++) oversized.accept(hevcPacket(large, i, 21000, false)); }, "oversized H265 access unit");
        List<byte[]> sets = Arrays.asList(new byte[] {0x40, 1, 10}, new byte[] {0x42, 1, 20}, new byte[] {0x44, 1, 30});
        check(Arrays.equals(Protocol.hevcConfiguration(sets), new byte[] {0, 0, 0, 1, 0x40, 1, 10, 0, 0, 0, 1, 0x42, 1, 20, 0, 0, 0, 1, 0x44, 1, 30}), "H265 csd-0 concatenates Annex B VPS/SPS/PPS");
        rejects(() -> Protocol.hevcConfiguration(sets.subList(0, 2)), "incomplete H265 SDP configuration");
        rejects(() -> Protocol.hevcConfiguration(Arrays.asList(new byte[] {0x26, 1})), "non-parameter H265 configuration NAL");
        rejects(() -> Protocol.hevcConfiguration(Arrays.asList(new byte[20000])), "oversized H265 parameter NAL");
        String text = "m=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=fmtp:98 sprop-max-don-diff=0; sprop-vps=QA==; sprop-sps=Qg==; sprop-pps=RA==\r\na=control:trackID=0\r\n";
        Sdp.Track track = Sdp.parse(text).get(0);
        check(Sdp.videoMime(track).equals("video/hevc") && track.fmtp.containsKey("sprop-vps"), "H265 SDP dispatch and parameter-set metadata");
        track.fmtp.remove("sprop-max-don-diff"); check(Sdp.videoMime(track).equals("video/hevc"), "H265 absent DON parameter defaults to zero");
        track.fmtp.put("sprop-max-don-diff", "1"); rejects(() -> Sdp.videoMime(track), "H265 DON interleaving");
        track.fmtp.put("sprop-max-don-diff", "secret-invalid-value");
        try { Sdp.videoMime(track); throw new AssertionError("Invalid DON accepted"); }
        catch (IOException e) { check(!Failures.describe(e, "missing").contains("secret-invalid"), "H265 SDP errors do not print input"); }
        track.codec = "H264"; check(Sdp.videoMime(track).equals("video/avc"), "H264 decoder selection retained");
        track.codec = "SECRET_CODEC"; rejects(() -> Sdp.videoMime(track), "unsupported codec without exposing raw name");
    }
    static void streamValidationTests() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("console", "https://console.example"); config.put("apiKey", "DO_NOT_DISPLAY_API_KEY");
        Settings settings = new Settings(config);
        Object[][] cases = {
            {JSONObject.NULL, "unavailable"},
            {"", "unavailable"},
            {new JSONObject().put("url", "rtsps://secret-host/secret-path"), "unsupported stream value"},
            {new JSONArray().put("secret-array-token"), "unsupported stream value"},
            {17, "unsupported stream value"},
            {true, "unsupported stream value"},
            {"rtsps://secret-host/invalid path?secret-query", "invalid stream URL"},
            {"rtsp://secret-host:7447/secret-path?secret-query", "RTSPS is required"},
            {"https://secret-host/secret-path", "RTSPS is required"},
            {"secret-scheme:secret-path", "RTSPS is required"},
            {"secret-path", "RTSPS is required"},
            {"rtsps:secret-path", "host is missing or invalid"},
            {"rtsps://secret_host/secret-path", "host is missing or invalid"},
            {"rtsps://secret-user:secret-password@secret-host:7441/secret-path", "credentials are not supported"},
            {"rtsps://secret-host:7441/secret-path?secret-query#secret-fragment", "URL fragments are not supported"}
        };
        for (Object[] test : cases) {
            try {
                ProtectApi.streamUrl(new JSONObject().put("medium", test[0]), settings);
                throw new AssertionError("Expected stream format rejection: " + test[1]);
            } catch (IOException e) {
                String message = Failures.describe(e, "Validation failed");
                check(message.contains((String) test[1]), "Clear stream error reaches UI");
                check(!message.contains("secret-") && !message.contains("DO_NOT_DISPLAY") && !message.contains("secret_host"), "Stream errors do not disclose secret components");
                check(message.length() <= 1000, "Stream error fits status limit");
                check(e.getCause() == null, "URI/parser cause containing secret URL is not retained");
            }
        }
        try { ProtectApi.streamUrl(new JSONObject(), settings); throw new AssertionError("Missing stream accepted"); }
        catch (IOException e) { check(e.getMessage().contains("unavailable"), "Missing quality has a clear error without debug fields"); }
        URI parsed = ProtectApi.streamUrl(new JSONObject().put("medium", "rtsps://secret-host:7441/secret-path?enableSrtp"), settings);
        check(parsed.getHost().equals("console.example") && parsed.getPort() == 7441 && parsed.getPath().equals("/secret-path") && parsed.getQuery() == null, "Valid RTSPS behavior unchanged");
        URI query = ProtectApi.streamUrl(new JSONObject().put("medium", "rtsps://secret-host:7441/secret-path?token=secret-query"), settings);
        check(query.getQuery().equals("token=secret-query"), "Unrelated stream query preserved internally, not printed");
    }
    static void videoTlsTests(String keystore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(keystore))) { keys.load(in, "test-only".toCharArray()); }
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(keys, "test-only".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(manager.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("console", "https://127.0.0.1:" + server.getAddress().getPort());
            config.put("apiKey", "MUST_NOT_BE_SENT_ON_VIDEO");
            URI video = URI.create("rtsps://127.0.0.1:" + server.getAddress().getPort() + "/mock-stream");
            for (String pin : new String[] {"", String.join("", Collections.nCopies(64, "0"))}) {
                config.put("certificatePin", pin);
                Settings settings = new Settings(config);
                SecureTransport transport = new SecureTransport(settings);
                try (SSLSocket socket = (SSLSocket) transport.rtsp(video)) {
                    check(socket.getSession().getPeerPrincipal().getName().contains("unifi.local"), "RTSPS accepts Protect-like self-signed certificate with mismatched hostname");
                    check(socket.getSSLParameters().getEndpointIdentificationAlgorithm() == null, "RTSPS hostname verification deliberately disabled");
                    check(socket.getSession().getProtocol().startsWith("TLS"), "RTSPS remains encrypted");
                }
                // Neither unverified video context nor an API pin may weaken HTTPS API TLS.
                try (ProtectApi api = new ProtectApi(settings)) { rejects(api::cameras, "API must still reject self-signed certificate/wrong pin after video TLS"); }
                rejects(() -> transport.rtsp(URI.create("rtsps://127.0.0.2:7441/mock-stream")), "video must retain configured-host restriction");
            }
            check(requests.get() == 0, "Video TLS test sends no HTTP/API key; invalid API TLS prevents requests");
        } finally { server.stop(0); }
    }
    static void tlsTests(String keystore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(keystore))) { keys.load(in, "test-only".toCharArray()); }
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(keys, "test-only".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(manager.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        AtomicInteger posts = new AtomicInteger(), requests = new AtomicInteger(), redirects = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            int status = 200; String body = "{}";
            if (!"test-only-key".equals(exchange.getRequestHeaders().getFirst("X-API-KEY"))) { status = 401; }
            else if (path.endsWith("/cameras")) body = "[{\"id\":\"cam1\",\"type\":\"G4 Doorbell Pro\",\"name\":\"Test camera\"}]";
            else if (path.endsWith("/cam1/rtsps-stream")) body = "{\"medium\":\"rtsps://other-console-interface:7441/secret-stream?enableSrtp\"}";
            else if (path.endsWith("/cam1/talkback-session") && "POST".equals(exchange.getRequestMethod())) { posts.incrementAndGet(); body = "{\"codec\":\"aac\",\"samplingRate\":16000,\"url\":\"udp://camera.example:5000\"}"; }
            else if (path.endsWith("/cam1")) body = "{\"featureFlags\":{\"hasSpeaker\":true}}";
            else if (path.endsWith("/redirect")) { status = 302; exchange.getResponseHeaders().add("Location", "/stolen"); }
            else if (path.endsWith("/stolen")) redirects.incrementAndGet();
            else { status = 404; body = "secret-server-error"; }
            byte[] bytes = body.getBytes("UTF-8"); exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            Map<String, Object> m = new HashMap<>(); m.put("console", "https://127.0.0.1:" + server.getAddress().getPort());
            m.put("apiKey", "test-only-key"); m.put("cameraId", "cam1");
            String pin = SecureTransport.hex(MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("test").getEncoded()));
            m.put("certificatePin", pin);
            try (ProtectApi api = new ProtectApi(new Settings(m))) {
                check(api.cameras().length() == 1, "Pinned HTTPS camera list");
                check(api.camera().getJSONObject("featureFlags").getBoolean("hasSpeaker"), "Camera speaker metadata");
                URI stream = api.stream();
                check(stream.getHost().equals("127.0.0.1") && stream.getRawQuery() == null && stream.getPath().equals("/secret-stream"), "Stream host override and exact SRTP flag removal");
                check(posts.get() == 0, "Passive checks never start talkback");
                check(api.talkback().getString("codec").equals("aac") && posts.get() == 1, "Explicit talkback POST against mock only");
                api.close(); rejects(api::cameras, "request after close");
            }
            int before = requests.get();
            m.put("certificatePin", "0000000000000000000000000000000000000000000000000000000000000000");
            try (ProtectApi api = new ProtectApi(new Settings(m))) { rejects(api::cameras, "certificate mismatch"); }
            check(requests.get() == before, "No HTTP/API key sent on TLS pin mismatch");
            m.put("certificatePin", "");
            try (ProtectApi api = new ProtectApi(new Settings(m))) { rejects(api::cameras, "untrusted TLS without pin"); }
            check(requests.get() == before, "No HTTP/API key sent to untrusted TLS");
            m.put("certificatePin", pin); m.put("apiKey", "wrong-key");
            try (ProtectApi api = new ProtectApi(new Settings(m))) { rejects(api::cameras, "unauthorized API key"); }
            m.put("apiKey", "test-only-key"); m.put("cameraId", "redirect");
            try (ProtectApi api = new ProtectApi(new Settings(m))) { rejects(api::camera, "HTTP redirect"); }
            check(redirects.get() == 0, "HTTP redirects never forward API key");
            m.put("cameraId", "missing");
            try (ProtectApi api = new ProtectApi(new Settings(m))) {
                try { api.camera(); throw new AssertionError("Expected 404"); }
                catch (IOException e) { check(!e.getMessage().contains("secret-server-error"), "Error response body redacted"); }
            }
            m.put("cameraId", "cam1");
            int beforePluginPosts = posts.get();
            passed += PluginTest.run(m);
            SetupTest.network(m, requests);
            check(posts.get() == beforePluginPosts, "Plugin lifecycle and guided setup never create talkback sessions");
        } finally { server.stop(0); }
    }
}
