// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.*;
import java.util.*;

final class VideoParametersTest {
    static void run() throws Exception {
        for (int[] size : new int[][] {{480, 360}, {1280, 720}, {1600, 1200}, {1920, 1080}, {3840, 2160}}) {
            for (boolean hevc : new boolean[] {false, true}) {
                byte[] nal = hevc ? hevc(size[0], size[1], 1, 8, 120) : avc(size[0], size[1]);
                VideoParameters p = VideoParameters.sps(nal, hevc);
                CoreTest.check(p.displayWidth == size[0] && p.displayHeight == size[1], "Real " + (hevc ? "HEVC" : "AVC") + " SPS dimensions " + size[0] + "x" + size[1]);
                CoreTest.check(p.width >= p.displayWidth && p.height >= p.displayHeight && p.bitDepth == 8 && p.chroma == 1, "Coded dimensions/crop and sample format");
                CoreTest.check(p.profile == (hevc ? 1 : 66) && p.level == (hevc ? 120 : 40), "Profile/level read before decoder selection");
                CoreTest.rejects(() -> VideoParameters.sps(Arrays.copyOf(nal, 3), hevc), "truncated SPS");
            }
        }
        VideoParameters ten = VideoParameters.sps(hevc(1600, 1200, 2, 10, 123), true);
        CoreTest.check(ten.bitDepth == 10 && ten.profile == 2 && ten.level == 123, "HEVC Main10 profile and bit depth");
        CoreTest.rejects(() -> VideoParameters.sps(hevc(9000, 1200, 1, 8, 120), true), "oversized dimensions");
        CoreTest.rejects(() -> VideoParameters.sps(new byte[] {0x67, 0, 0, 3, 4}, false), "invalid emulation prevention");
        byte[] nal = hevc(1600, 1200, 1, 8, 120), joined = new byte[nal.length + 4 + 5]; joined[3] = 1;
        System.arraycopy(nal, 0, joined, 4, nal.length); joined[joined.length - 2] = 1; joined[joined.length - 1] = 0x26;
        List<byte[]> split = VideoParameters.annexB(joined);
        CoreTest.check(split.size() == 2 && Arrays.equals(split.get(0), nal), "Annex B extraction supports in-band SPS and mixed start codes");
        List<DecoderOrder.Candidate> ordered = DecoderOrder.choose(Arrays.asList(
            new DecoderOrder.Candidate("software", true, true), new DecoderOrder.Candidate("bad-hardware", false, false),
            new DecoderOrder.Candidate("hardware", false, true), new DecoderOrder.Candidate("software2", true, true)));
        CoreTest.check(ordered.size() == 3 && ordered.get(0).name.equals("hardware") && ordered.get(1).name.equals("software"), "Compatible hardware first, software retained only as fallback");
        CoreTest.check(DecoderOrder.choose(Collections.singletonList(new DecoderOrder.Candidate("hardware", false, false))).isEmpty(), "Unsupported hardware is never selected");
        CoreTest.check(DecoderOrder.software("OMX.google.hevc.decoder") && DecoderOrder.software("c2.android.hevc.decoder") && !DecoderOrder.software("OMX.MTK.VIDEO.DECODER.HEVC"), "Pre-29 software codec classification");
        // Deterministic malformed data must fail boundedly, never hang or allocate by untrusted size.
        Random random = new Random(18);
        for (int i = 0; i < 100; i++) { byte[] malformed = new byte[random.nextInt(80) + 1]; random.nextBytes(malformed); malformed[0] = 0x42;
            try { VideoParameters.sps(malformed, true); } catch (IOException expected) {} }
        CoreTest.check(true, "Bounded malformed SPS corpus");
    }
    static byte[] avc(int width, int height) {
        Writer b = new Writer(); b.n(66, 8); b.n(0, 8); b.n(40, 8); b.ue(0); b.ue(0); b.ue(0); b.ue(0); b.ue(1); b.n(0, 1);
        b.ue((width + 15) / 16 - 1); b.ue((height + 15) / 16 - 1); b.n(1, 1); b.n(1, 1);
        int crop = ((height + 15) / 16 * 16 - height) / 2;
        b.n(crop == 0 ? 0 : 1, 1); if (crop != 0) { b.ue(0); b.ue(0); b.ue(0); b.ue(crop); }
        return b.nal(new byte[] {0x67});
    }
    static byte[] hevc(int width, int height, int profile, int bits, int level) {
        Writer b = new Writer(); b.n(0, 4); b.n(0, 3); b.n(1, 1); b.n(0, 2); b.n(0, 1); b.n(profile, 5);
        for (int i = 0; i < 10; i++) b.n(0, 8); b.n(level, 8);
        b.ue(0); b.ue(1); b.ue(width); b.ue(height); b.n(0, 1); b.ue(bits - 8); b.ue(bits - 8);
        return b.nal(new byte[] {0x42, 1});
    }
    static final class Writer {
        final StringBuilder bits = new StringBuilder();
        void n(int value, int count) { for (int i = count - 1; i >= 0; i--) bits.append((value >> i) & 1); }
        void ue(int value) { String code = Integer.toBinaryString(value + 1); bits.append("0".repeat(code.length() - 1)).append(code); }
        byte[] nal(byte[] header) {
            bits.append('1'); while (bits.length() % 8 != 0) bits.append('0');
            ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(header, 0, header.length); int zeros = 0;
            for (int i = 0; i < bits.length(); i += 8) {
                int value = Integer.parseInt(bits.substring(i, i + 8), 2);
                if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0; }
                out.write(value); zeros = value == 0 ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }
    }
}
