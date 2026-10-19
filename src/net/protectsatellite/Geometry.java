// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

final class Geometry {
    // Returns x, y, width, height. Position 0 anchors left/top, 1 right/bottom.
    static float[] image(float boxW, float boxH, float imageW, float imageH, String mode, float x, float y) {
        if (boxW <= 0 || boxH <= 0 || imageW <= 0 || imageH <= 0) return new float[] {0, 0, boxW, boxH};
        if ("Stretch".equals(mode)) return new float[] {0, 0, boxW, boxH};
        float scale = "Fit".equals(mode) ? Math.min(boxW / imageW, boxH / imageH) : Math.max(boxW / imageW, boxH / imageH);
        float w = imageW * scale, h = imageH * scale;
        return new float[] {(boxW - w) * x, (boxH - h) * y, w, h};
    }
    static float aspect(String value) {
        if ("4:3".equals(value)) return 4f / 3;
        if ("16:9".equals(value)) return 16f / 9;
        if ("16:10".equals(value)) return 16f / 10;
        return 0;
    }
}
