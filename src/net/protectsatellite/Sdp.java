// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.IOException;
import java.net.URI;
import java.util.*;

final class Sdp {
    static String videoMime(Track track) throws IOException {
        if ("H264".equals(track.codec)) return "video/avc";
        if (!"H265".equals(track.codec)) throw new IOException("Camera video codec is not supported; expected H264 or H265");
        String don = track.fmtp.getOrDefault("sprop-max-don-diff", "0");
        try {
            if (Integer.parseInt(don) != 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { throw new IOException("H265 interleaved/DON mode is not supported; requires sprop-max-don-diff=0"); }
        return "video/hevc";
    }
    static final class Track {
        String kind, codec = "", control = "";
        int payload, rate, channels = 1;
        final Map<String, String> fmtp = new HashMap<>();
    }
    static List<Track> parse(String text) throws IOException {
        List<Track> tracks = new ArrayList<>(); Track t = null;
        try {
            for (String raw : text.split("\r?\n")) {
                String l = raw.trim();
                if (l.startsWith("m=")) {
                    String[] p = l.substring(2).split(" +");
                    if (p.length < 4 || !"RTP/AVP".equals(p[2])) throw new IOException("Unsupported secure/multipayload SDP transport");
                    t = new Track(); t.kind = p[0]; t.payload = Integer.parseInt(p[3]); tracks.add(t);
                } else if (t != null && l.startsWith("a=control:")) t.control = l.substring(10);
                else if (t != null && l.startsWith("a=rtpmap:" + t.payload + " ")) {
                    String[] p = l.substring(l.indexOf(' ') + 1).split("/");
                    t.codec = p[0].toUpperCase(Locale.ROOT); t.rate = Integer.parseInt(p[1]);
                    if (p.length > 2) t.channels = Integer.parseInt(p[2]);
                } else if (t != null && l.startsWith("a=fmtp:" + t.payload + " ")) {
                    for (String pair : l.substring(l.indexOf(' ') + 1).split(";")) {
                        int at = pair.indexOf('='); if (at > 0) t.fmtp.put(pair.substring(0, at).trim().toLowerCase(Locale.ROOT), pair.substring(at + 1).trim());
                    }
                }
            }
        } catch (RuntimeException e) { throw new IOException("Malformed stream SDP"); }
        for (Track track : tracks) if (track.rate <= 0 || track.payload < 0 || track.payload > 127 || track.control.isEmpty())
            throw new IOException("Incomplete stream track description");
        return tracks;
    }
    static URI control(URI base, String value) throws IOException {
        try {
            URI u = value.startsWith("rtsp:") || value.startsWith("rtsps:") ? URI.create(value)
                : URI.create(base.toString().endsWith("/") ? base.toString() : base.toString() + "/").resolve(value);
            if (u.getHost() == null || !u.getHost().equalsIgnoreCase(base.getHost()) || u.getUserInfo() != null
                    || u.getRawFragment() != null || u.getPort() != base.getPort()
                    || !("rtsp".equals(u.getScheme()) || "rtsps".equals(u.getScheme()))) throw new IllegalArgumentException();
            return u;
        } catch (RuntimeException e) { throw new IOException("Unsafe or unsupported RTSP control URL"); }
    }
    static byte[] hex(String s) throws IOException {
        if (s == null || s.length() == 0 || s.length() % 2 != 0 || s.length() > 4096) throw new IOException("Missing or invalid codec configuration");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IOException("Invalid codec configuration"); b[i] = (byte) (hi * 16 + lo);
        } return b;
    }
}
