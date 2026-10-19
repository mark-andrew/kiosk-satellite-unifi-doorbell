// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;

/** Stable hardware-first order, retaining only explicitly compatible codecs. */
final class DecoderOrder {
    static final class Candidate {
        final String name; final boolean software, supported;
        Candidate(String name, boolean software, boolean supported) { this.name = name; this.software = software; this.supported = supported; }
    }
    static List<Candidate> choose(List<Candidate> candidates) {
        List<Candidate> selected = new ArrayList<>();
        for (Candidate c : candidates) if (c.supported && !c.software) selected.add(c);
        for (Candidate c : candidates) if (c.supported && c.software) selected.add(c);
        return selected;
    }
    static boolean software(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("c2.google.") || lower.contains(".sw.") || lower.contains("software");
    }
}
