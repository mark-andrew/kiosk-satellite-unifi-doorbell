// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

/** Bounded display text for settings, status messages and discovered labels. */
final class DisplayText {
    private DisplayText() {}
    static String limit(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }
    static String singleLine(String value, int max) { return limit(value.replaceAll("[\\p{Cntrl}]", " "), max); }
}
