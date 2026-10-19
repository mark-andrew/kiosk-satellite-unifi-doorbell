// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.IOException;
import java.net.*;
import java.util.*;
import org.json.JSONObject;

/** Validated talkback metadata. Never prints the session URL or its raw fields. */
final class TalkSession {
    final boolean opus;
    final int rate, port;
    final String host;
    TalkSession(JSONObject session) throws IOException {
        String codec = session.optString("codec", "").toLowerCase(Locale.ROOT);
        opus = "opus".equals(codec);
        if (!opus && !"aac".equals(codec)) throw new IOException("Talkback requires AAC/UDP or Opus/RTP; the negotiated codec is not supported");
        rate = session.optInt("samplingRate", 0);
        if (session.optInt("bitsPerSample", 16) != 16
                || !(rate == 8000 || rate == 12000 || rate == 16000 || rate == 24000 || rate == 32000 || rate == 44100 || rate == 48000))
            throw new IOException("Unsupported negotiated talkback sampling parameters");
        URI target;
        try { target = URI.create(session.optString("url", "")); }
        catch (RuntimeException e) { throw new IOException("Invalid negotiated talkback destination"); }
        if (!(opus ? "rtp" : "udp").equals(target.getScheme()) || target.getHost() == null
                || target.getPort() < 1 || target.getPort() > 65535 || target.getRawUserInfo() != null
                || target.getRawQuery() != null || target.getRawFragment() != null
                || !(target.getPath().isEmpty() || "/".equals(target.getPath())))
            throw new IOException("Unsupported negotiated talkback URL format; no audio transmitted");
        host = target.getHost(); port = target.getPort();
    }
    String mime() { return opus ? "audio/opus" : "audio/mp4a-latm"; }
    int[] encoderRates() { return opus && rate != 48000 ? new int[] {rate, 48000} : new int[] {rate}; }
    byte[] packet(byte[] encoded, int sequence, long microseconds, int ssrc, int captureRate) throws IOException {
        // RFC 7587: Opus always uses a 48 kHz RTP clock, regardless of input rate.
        return opus ? Protocol.rtp(encoded, sequence & 65535, microseconds * 48000L / 1000000L, ssrc, 97)
            : Protocol.adts(encoded, captureRate, 1);
    }
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    /** Only call on metadata from the explicit, authenticated talkback POST.
     * Trust the console's negotiated destination. Resolve once and use/save
     * the same validated numeric address.
     */
    InetAddress authenticatedDestination() throws IOException { return authenticatedDestination(InetAddress::getAllByName); }
    InetAddress authenticatedDestination(Resolver resolver) throws IOException {
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) throw new IOException("Talkback destination did not resolve");
        for (InetAddress address : addresses) if (!TalkbackAddress.safe(address)) throw new IOException("Protect returned an unsafe talkback destination; no audio transmitted");
        return addresses[0];
    }
}
