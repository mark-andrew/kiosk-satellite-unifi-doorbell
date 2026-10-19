// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.*;
import java.util.*;

/** Bounded H.264/H.265 SPS parsing before Android decoder allocation. */
final class VideoParameters {
    final int width, height, displayWidth, displayHeight, profile, level, bitDepth, chroma;
    final boolean hevc, highTier;
    private VideoParameters(boolean hevc, boolean tier, int width, int height, int dw, int dh, int profile, int level, int bits, int chroma) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || dw < 1 || dh < 1 || dw > width || dh > height)
            throw new IOException("Video dimensions exceed safety limits");
        if (bits < 8 || bits > 12 || chroma > 3) throw new IOException("Unsupported video sample format");
        this.hevc = hevc; highTier = tier; this.width = width; this.height = height; displayWidth = dw; displayHeight = dh;
        this.profile = profile; this.level = level; bitDepth = bits; this.chroma = chroma;
    }
    static VideoParameters sps(byte[] nal, boolean hevc) throws IOException {
        if (nal.length < (hevc ? 4 : 5) || nal.length > 16384
                || (hevc ? (nal[0] & 0x7e) >> 1 != 33 : (nal[0] & 31) != 7)) throw new IOException("Invalid video SPS");
        Bits b = new Bits(nal, hevc ? 2 : 1);
        if (hevc) {
            b.skip(4); int layers = b.read(3); b.skip(1);
            if (layers > 6) throw new IOException("Invalid HEVC temporal layers");
            b.skip(2); boolean tier = b.read(1) != 0; int profile = b.read(5);
            b.skip(32 + 48); int level = b.read(8);
            boolean[] profiles = new boolean[layers], levels = new boolean[layers];
            for (int i = 0; i < layers; i++) { profiles[i] = b.read(1) != 0; levels[i] = b.read(1) != 0; }
            if (layers > 0) b.skip((8 - layers) * 2);
            for (int i = 0; i < layers; i++) { if (profiles[i]) b.skip(88); if (levels[i]) b.skip(8); }
            b.ue(); int chroma = b.ue(); if (chroma > 3) throw new IOException("Invalid HEVC chroma format");
            boolean separate = chroma == 3 && b.read(1) != 0;
            int width = b.ue(), height = b.ue(), left = 0, right = 0, top = 0, bottom = 0;
            if (b.read(1) != 0) { left = b.ue(); right = b.ue(); top = b.ue(); bottom = b.ue(); }
            int bits = b.ue() + 8, chromaBits = b.ue() + 8;
            if (bits != chromaBits) throw new IOException("Mixed video component bit depths are not supported");
            int subW = !separate && (chroma == 1 || chroma == 2) ? 2 : 1;
            int subH = !separate && chroma == 1 ? 2 : 1;
            return new VideoParameters(true, tier, width, height, width - subW * (left + right), height - subH * (top + bottom), profile, level, bits, chroma);
        }
        int profile = b.read(8); b.skip(8); int level = b.read(8); b.ue();
        int chroma = 1, bits = 8; boolean separate = false;
        if (Arrays.asList(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135).contains(profile)) {
            chroma = b.ue(); if (chroma > 3) throw new IOException("Invalid AVC chroma format");
            if (chroma == 3) separate = b.read(1) != 0;
            bits = b.ue() + 8; int chromaBits = b.ue() + 8;
            if (bits != chromaBits) throw new IOException("Mixed video component bit depths are not supported");
            b.skip(1);
            if (b.read(1) != 0) for (int i = 0; i < (chroma == 3 ? 12 : 8); i++) if (b.read(1) != 0) {
                int last = 8, next = 8;
                for (int j = 0; j < (i < 6 ? 16 : 64); j++) { if (next != 0) next = (last + b.se() + 256) & 255; last = next == 0 ? last : next; }
            }
        }
        b.ue(); int order = b.ue();
        if (order == 0) b.ue();
        else if (order == 1) { b.skip(1); b.se(); b.se(); int cycle = b.ue(); if (cycle > 255) throw new IOException("Invalid AVC picture order cycle"); for (int i = 0; i < cycle; i++) b.se(); }
        else if (order != 2) throw new IOException("Invalid AVC picture order");
        b.ue(); b.skip(1); int mbs = b.ue() + 1, maps = b.ue() + 1; int frameOnly = b.read(1);
        if (mbs > 512 || maps > 512) throw new IOException("Video dimensions exceed safety limits");
        if (frameOnly == 0) b.skip(1); b.skip(1);
        int left = 0, right = 0, top = 0, bottom = 0;
        if (b.read(1) != 0) { left = b.ue(); right = b.ue(); top = b.ue(); bottom = b.ue(); }
        int chromaArray = separate ? 0 : chroma;
        int cropW = chromaArray == 1 || chromaArray == 2 ? 2 : 1;
        int cropH = (chromaArray == 1 ? 2 : 1) * (2 - frameOnly);
        int width = mbs * 16, height = maps * 16 * (2 - frameOnly);
        return new VideoParameters(false, false, width, height, width - cropW * (left + right), height - cropH * (top + bottom), profile, level, bits, chroma);
    }
    static List<byte[]> annexB(byte[] data) throws IOException {
        if (data.length > Protocol.MAX_FRAME) throw new IOException("Video frame exceeds safety limit");
        List<byte[]> units = new ArrayList<>(); int start = -1;
        for (int i = 0; i + 2 < data.length; i++) {
            int size = data[i] == 0 && data[i + 1] == 0 ? (data[i + 2] == 1 ? 3 : (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0)) : 0;
            if (size == 0) continue;
            if (start >= 0 && i > start) units.add(Arrays.copyOfRange(data, start, i));
            start = i + size; i += size - 1;
            if (units.size() > 256) throw new IOException("Too many video NAL units");
        }
        if (start >= 0 && start < data.length) units.add(Arrays.copyOfRange(data, start, data.length));
        return units;
    }
    private static final class Bits {
        private final byte[] data; private int at;
        Bits(byte[] nal, int offset) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); int zeros = 0;
            for (int i = offset; i < nal.length; i++) {
                int value = nal[i] & 255;
                if (zeros >= 2 && value == 3) {
                    if (i + 1 >= nal.length || (nal[i + 1] & 255) > 3) throw new IOException("Invalid video emulation-prevention byte");
                    zeros = 0; continue;
                }
                out.write(value); zeros = value == 0 ? zeros + 1 : 0;
            }
            data = out.toByteArray();
        }
        int read(int count) throws IOException {
            if (count < 0 || count > 31 || at + count > data.length * 8) throw new IOException("Truncated video SPS");
            int value = 0; for (int i = 0; i < count; i++, at++) value = value << 1 | (data[at / 8] >> (7 - at % 8) & 1); return value;
        }
        void skip(int count) throws IOException { if (count < 0 || at + count > data.length * 8) throw new IOException("Truncated video SPS"); at += count; }
        int ue() throws IOException { int zeros = 0; while (read(1) == 0) if (++zeros > 20) throw new IOException("Video SPS value exceeds safety limit"); return (1 << zeros) - 1 + read(zeros); }
        int se() throws IOException { int n = ue(); return (n & 1) != 0 ? (n + 1) / 2 : -n / 2; }
    }
}
