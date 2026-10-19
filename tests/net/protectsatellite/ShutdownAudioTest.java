// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

final class ShutdownAudioTest {
    static void run() throws Exception {
        Thread caller = Thread.currentThread(); AtomicReference<Thread> used = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        NetworkCleanup.Task cleanup = new NetworkCleanup.Task(() -> {
            used.set(Thread.currentThread()); calls.incrementAndGet(); started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test cleanup not released");
        });
        try {
            cleanup.close();
            CoreTest.check(started.await(3, TimeUnit.SECONDS), "Cancellation network cleanup starts asynchronously");
            CoreTest.check(used.get() != caller && used.get().getName().equals("protect-network-cleanup"), "TLS close never runs on its UI/calling thread");
            CoreTest.check(!cleanup.await(1, TimeUnit.MILLISECONDS), "UI close returns without waiting for a blocking socket close");
            ExecutorService racers = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> attempts = new ArrayList<>();
                for (int i = 0; i < 20; i++) attempts.add(racers.submit(cleanup::close));
                for (Future<?> attempt : attempts) attempt.get(2, TimeUnit.SECONDS);
            } finally { racers.shutdownNow(); }
            CoreTest.check(calls.get() == 1, "Back/detach/finally repeated close releases network resource once");
        } finally { release.countDown(); }
        CoreTest.check(cleanup.await(3, TimeUnit.SECONDS), "Asynchronous socket cleanup completes");
        NetworkCleanup.Task broken = new NetworkCleanup.Task(() -> { throw new IOException("secret-stream-url"); });
        broken.close(); CoreTest.check(broken.await(3, TimeUnit.SECONDS), "Close failure is contained, not an uncaught UI exception");
        AtomicInteger late = new AtomicInteger();
        NetworkCleanup.Task afterCancellation = new NetworkCleanup.Task(late::incrementAndGet);
        afterCancellation.close(); afterCancellation.close();
        CoreTest.check(afterCancellation.await(3, TimeUnit.SECONDS) && late.get() == 1, "Late-acquired resource can still be released exactly once");
        AtomicReference<Thread> owner = new AtomicReference<>();
        NetworkCleanup.Task normalFinally = new NetworkCleanup.Task(() -> owner.set(Thread.currentThread()));
        normalFinally.closeOnOwnerThread(); normalFinally.close();
        CoreTest.check(owner.get() == caller && normalFinally.await(1, TimeUnit.SECONDS), "Normal HTTP finally remains on its network-owner thread");

        TalkSession aac = session("aac", 16000, "udp://console-ip:5000");
        CoreTest.check(!aac.opus && aac.mime().equals("audio/mp4a-latm") && aac.encoderRates().length == 1, "AAC talkback format selection");
        CoreTest.check(Arrays.equals(aac.packet(new byte[] {1, 2, 3}, 1, 0, 2, 16000), Protocol.adts(new byte[] {1, 2, 3}, 16000, 1)), "Talkback AAC is ADTS/UDP, not RTP");
        TalkSession opus = session("opus", 24000, "rtp://console-ip:5000");
        CoreTest.check(opus.opus && Arrays.equals(opus.encoderRates(), new int[] {24000, 48000}), "Opus encoder can fall back to standard 48 kHz input");
        Protocol.Rtp rtp = new Protocol.Rtp(opus.packet(new byte[] {10, 11}, 65537, 20000, 123, 24000));
        CoreTest.check(rtp.timestamp == 960 && rtp.sequence == 1 && rtp.payloadType == 97, "Opus RTP always uses 48 kHz clock and wraps sequence");
        CoreTest.check(rtp.length == 2 && rtp.data[rtp.offset] == 10, "Opus packet payload preserved");
        CoreTest.check(session("opus", 48000, "rtp://console-ip:5000").encoderRates().length == 1, "Opus 48 kHz configuration is not retried twice");
        for (String url : new String[] {"rtp://console-ip:5000", "udp://console-ip:0", "udp://console-ip:65536", "udp://user:secret@console-ip:5000", "udp://console-ip:5000/path", "udp://console-ip:5000?secret=x", "udp://console-ip:5000#secret", "udp://[invalid"})
            CoreTest.rejects(() -> session("aac", 16000, url), "invalid talkback destination shape");
        CoreTest.rejects(() -> session("vorbis", 16000, "udp://console-ip:5000"), "unsupported negotiated talkback codec");
        CoreTest.rejects(() -> session("aac", 99999, "udp://console-ip:5000"), "unsupported negotiated sampling rate");
        CoreTest.rejects(() -> new TalkSession(new JSONObject().put("codec", "aac").put("samplingRate", 16000).put("bitsPerSample", 24).put("url", "udp://console-ip:5000")), "unsupported PCM width");
        TalkSession.Resolver resolver = host -> {
            if (host.equals("console-ip")) return new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 0, 1, 1})};
            if (host.equals("camera-ip")) return new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 0, 1, 20})};
            if (host.equals("loopback")) return new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})};
            if (host.equals("multicast")) return new InetAddress[] {InetAddress.getByAddress(new byte[] {(byte) 224, 0, 0, 1})};
            return new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 99, 99, 99})};
        };
        CoreTest.check(aac.authenticatedDestination(resolver).getHostAddress().equals("10.0.1.1"), "Authenticated negotiation can target the console");
        CoreTest.check(session("aac", 16000, "udp://camera-ip:5000").authenticatedDestination(resolver).getHostAddress().equals("10.0.1.20"), "Authenticated negotiation can target the doorbell without a manual address");
        for (String host : new String[] {"loopback", "multicast"})
            CoreTest.rejects(() -> session("aac", 16000, "udp://" + host + ":5000").authenticatedDestination(resolver), "special talkback target");
        try { session("secret-codec", 0, "udp://secret-host:5000"); throw new AssertionError("Invalid session accepted"); }
        catch (IOException e) { CoreTest.check(!Failures.describe(e, "fallback").contains("secret"), "Talkback errors never expose raw session fields"); }
    }
    private static TalkSession session(String codec, int rate, String url) throws Exception {
        return new TalkSession(new JSONObject().put("codec", codec).put("samplingRate", rate).put("url", url));
    }
}
