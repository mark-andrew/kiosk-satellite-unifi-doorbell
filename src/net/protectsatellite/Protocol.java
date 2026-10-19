// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.*;
import java.util.*;

/** Bounded, dependency-free RTSP/RTP framing. Also exercised by JVM tests. */
final class Protocol {
    static final int MAX_FRAME = 2 * 1024 * 1024;
    static final class Message {
        int channel = -1, status;
        final Map<String, String> headers = new HashMap<>();
        byte[] body;
    }
    static Message read(InputStream in) throws IOException {
        int first = in.read(); if (first < 0) throw new EOFException("Stream disconnected");
        Message m = new Message();
        if (first == '$') {
            m.channel = readByte(in); int size = readByte(in) * 256 + readByte(in);
            m.body = bytes(in, size); return m;
        }
        String start = line(in, first);
        String[] parts = start.split(" ");
        if (parts.length < 2 || !parts[0].equals("RTSP/1.0")) throw new IOException("Unexpected RTSP response");
        try { m.status = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { throw new IOException("Invalid RTSP status"); }
        int total = 0;
        for (;;) {
            String l = line(in, readByte(in)); total += l.length();
            if (total > 65536) throw new IOException("RTSP headers too large");
            if (l.isEmpty()) break;
            int colon = l.indexOf(':'); if (colon <= 0) throw new IOException("Invalid RTSP header");
            m.headers.put(l.substring(0, colon).toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
        }
        int length;
        try { length = Integer.parseInt(m.headers.getOrDefault("content-length", "0")); }
        catch (NumberFormatException e) { throw new IOException("Invalid RTSP content length"); }
        if (length < 0 || length > MAX_FRAME) throw new IOException("RTSP response exceeds safety limit");
        m.body = bytes(in, length); return m;
    }
    private static String line(InputStream in, int first) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c = first;
        while (c != '\n') { if (b.size() >= 8192) throw new IOException("RTSP line too large"); b.write(c); c = readByte(in); }
        byte[] data = b.toByteArray(); int size = data.length;
        if (size > 0 && data[size - 1] == '\r') size--;
        return new String(data, 0, size, "UTF-8");
    }
    static int readByte(InputStream in) throws IOException { int n = in.read(); if (n < 0) throw new EOFException("Stream disconnected"); return n; }
    static byte[] bytes(InputStream in, int size) throws IOException {
        byte[] b = new byte[size]; int n = 0;
        while (n < size) { int got = in.read(b, n, size - n); if (got < 0) throw new EOFException("Incomplete stream packet"); n += got; } return b;
    }
    static final class Rtp {
        final byte[] data; final int offset, length, sequence, payloadType; final long timestamp; final boolean marker;
        Rtp(byte[] b) throws IOException {
            if (b.length < 12 || (b[0] & 0xc0) != 0x80) throw new IOException("Invalid RTP header");
            data = b; int off = 12 + (b[0] & 15) * 4;
            if (off > b.length) throw new IOException("Invalid RTP CSRC list");
            if ((b[0] & 0x10) != 0) { if (off + 4 > b.length) throw new IOException("Truncated RTP extension"); off += 4 + u16(b, off + 2) * 4; }
            int end = b.length;
            if ((b[0] & 0x20) != 0) { int padding = b[end - 1] & 255; if (padding == 0 || padding > end - off) throw new IOException("Invalid RTP padding"); end -= padding; }
            if (off > end) throw new IOException("Invalid RTP payload");
            offset = off; length = end - off; sequence = u16(b, 2); payloadType = b[1] & 127; marker = (b[1] & 128) != 0;
            timestamp = ((long) u16(b, 4) << 16) | u16(b, 6);
        }
    }
    static int u16(byte[] b, int at) { return ((b[at] & 255) << 8) | (b[at + 1] & 255); }
    static byte[] rtp(byte[] payload, int sequence, long timestamp, int ssrc, int type) {
        byte[] b = new byte[12 + payload.length]; b[0] = (byte) 0x80; b[1] = (byte) type;
        b[2] = (byte) (sequence >>> 8); b[3] = (byte) sequence;
        for (int i = 0; i < 4; i++) { b[4 + i] = (byte) (timestamp >>> (24 - i * 8)); b[8 + i] = (byte) (ssrc >>> (24 - i * 8)); }
        System.arraycopy(payload, 0, b, 12, payload.length); return b;
    }
    static final class H264 {
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private boolean fragment;
        private int previous = -1;
        private long timestamp = -1;
        byte[] accept(Rtp p) throws IOException {
            if (previous >= 0 && p.sequence != ((previous + 1) & 65535)) { reset(); }
            previous = p.sequence;
            if (timestamp != p.timestamp) { frame.reset(); fragment = false; timestamp = p.timestamp; }
            if (p.length == 0) return null;
            byte[] b = p.data; int off = p.offset, end = off + p.length, type = b[off] & 31;
            if (type >= 1 && type <= 23) { start(); append(b, off, p.length); }
            else if (type == 24) {
                int at = off + 1;
                while (at < end) {
                    if (at + 2 > end) throw new IOException("Invalid H264 aggregation");
                    int n = u16(b, at); at += 2;
                    if (n == 0 || at + n > end) throw new IOException("Invalid H264 aggregation length");
                    start(); append(b, at, n); at += n;
                }
            } else if (type == 28) {
                if (p.length < 3) throw new IOException("Invalid H264 fragment");
                int header = b[off + 1] & 255;
                if ((header & 128) != 0) { fragment = true; start(); frame.write((b[off] & 0xe0) | (header & 31)); }
                if (!fragment) return null;
                append(b, off + 2, p.length - 2);
                if ((header & 64) != 0) fragment = false;
            } else throw new IOException("Unsupported H264 RTP packetization");
            if (!p.marker || fragment) return null;
            byte[] accessUnit = frame.toByteArray(); frame.reset(); return accessUnit;
        }
        private void start() { frame.write(0); frame.write(0); frame.write(0); frame.write(1); }
        private void append(byte[] b, int o, int n) throws IOException { if (frame.size() + n > MAX_FRAME) { reset(); throw new IOException("H264 frame exceeds safety limit"); } frame.write(b, o, n); }
        private void reset() { frame.reset(); fragment = false; timestamp = -1; }
    }
    /** RFC 7798 single NAL, AP and FU; non-interleaved mode without DON fields. */
    static final class H265 {
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private int previous = -1, fragmentHeader0, fragmentHeader1;
        private long timestamp = -1;
        private boolean fragment, damaged;
        byte[] accept(Rtp p) throws IOException {
            boolean newTimestamp = timestamp != p.timestamp;
            boolean gap = previous >= 0 && p.sequence != ((previous + 1) & 65535);
            previous = p.sequence;
            if (newTimestamp) { frame.reset(); fragment = false; damaged = false; timestamp = p.timestamp; }
            if (gap && !newTimestamp) damage();
            if (damaged) return null;
            if (p.length < 2) throw new IOException("Truncated H265 NAL header");
            byte[] b = p.data; int off = p.offset, end = off + p.length;
            header(b, off);
            int type = (b[off] & 0x7e) >>> 1;
            if (type < 48) {
                if (fragment) { damage(); return null; }
                start(); append(b, off, p.length);
            } else if (type == 48) {
                if (fragment) { damage(); return null; }
                int at = off + 2, count = 0;
                while (at < end) {
                    if (at + 2 > end) throw new IOException("Truncated H265 aggregation length");
                    int n = u16(b, at); at += 2;
                    if (n < 2 || at + n > end) throw new IOException("Invalid H265 aggregation length");
                    header(b, at);
                    if (((b[at] & 0x7e) >>> 1) >= 48) throw new IOException("Nested H265 packetization is not supported");
                    start(); append(b, at, n); at += n; count++;
                }
                if (count < 2) throw new IOException("H265 aggregation requires at least two NAL units");
            } else if (type == 49) {
                if (p.length < 4) throw new IOException("Truncated H265 fragmentation unit");
                int fu = b[off + 2] & 255, originalType = fu & 63;
                boolean begins = (fu & 128) != 0, ends = (fu & 64) != 0;
                if (originalType >= 48 || begins && ends) throw new IOException("Invalid H265 fragmentation header");
                int h0 = (b[off] & 0x81) | originalType << 1, h1 = b[off + 1] & 255;
                if (begins) {
                    if (fragment) { damage(); return null; }
                    fragment = true; fragmentHeader0 = h0; fragmentHeader1 = h1;
                    start(); append(new byte[] {(byte) h0, (byte) h1}, 0, 2);
                } else if (!fragment) { damage(); return null; }
                if (h0 != fragmentHeader0 || h1 != fragmentHeader1) throw new IOException("H265 fragment headers changed within a NAL unit");
                append(b, off + 3, p.length - 3);
                if (ends) fragment = false;
            } else throw new IOException("Unsupported H265 RTP packetization (PACI or reserved type)");
            if (p.marker && fragment) { damage(); return null; }
            if (!p.marker) return null;
            byte[] accessUnit = frame.toByteArray(); frame.reset(); return accessUnit;
        }
        private void damage() { frame.reset(); fragment = false; damaged = true; }
        private void start() throws IOException { append(new byte[] {0, 0, 0, 1}, 0, 4); }
        private void append(byte[] b, int o, int n) throws IOException {
            if (frame.size() + n > MAX_FRAME) { damage(); throw new IOException("H265 frame exceeds safety limit"); }
            frame.write(b, o, n);
        }
        private static void header(byte[] b, int at) throws IOException {
            if ((b[at] & 0x80) != 0 || (b[at + 1] & 7) == 0) throw new IOException("Invalid H265 NAL header");
        }
    }
    static byte[] hevcConfiguration(List<byte[]> parameters) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean vps = false, sps = false, pps = false;
        for (byte[] nal : parameters) {
            if (nal.length < 2 || nal.length > 16384) throw new IOException("Invalid H265 parameter-set length");
            H265.header(nal, 0);
            int type = (nal[0] & 0x7e) >>> 1;
            if (type == 32) vps = true;
            else if (type == 33) sps = true;
            else if (type == 34) pps = true;
            else throw new IOException("Unexpected H265 parameter-set NAL type");
            if (out.size() + nal.length + 4 > 65536) throw new IOException("H265 parameter sets exceed safety limit");
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(nal, 0, nal.length);
        }
        if (!vps || !sps || !pps) throw new IOException("H265 SDP parameter sets must contain VPS, SPS and PPS");
        return out.toByteArray();
    }
    static byte[] adts(byte[] aac, int sampleRate, int channels) throws IOException {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        int index = -1; for (int i = 0; i < rates.length; i++) if (rates[i] == sampleRate) index = i;
        int size = aac.length + 7;
        if (index < 0 || size > 8191 || channels < 1 || channels > 2) throw new IOException("Unsupported AAC ADTS parameters");
        byte[] b = new byte[size]; b[0] = (byte) 0xff; b[1] = (byte) 0xf1;
        b[2] = (byte) ((1 << 6) | (index << 2) | (channels >>> 2));
        b[3] = (byte) ((channels << 6) | (size >>> 11)); b[4] = (byte) (size >>> 3); b[5] = (byte) (((size & 7) << 5) | 31); b[6] = (byte) 0xfc;
        System.arraycopy(aac, 0, b, 7, aac.length); return b;
    }
}
