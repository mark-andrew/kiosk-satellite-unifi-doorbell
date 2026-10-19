// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.view.Gravity;

final class ControlsLayout {
    static boolean vertical(String placement) { return "Left".equals(placement) || "Right".equals(placement); }
    static int gravity(String placement) {
        if ("Left".equals(placement)) return Gravity.LEFT | Gravity.CENTER_VERTICAL;
        if ("Right".equals(placement)) return Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        return ("Bottom".equals(placement) ? Gravity.BOTTOM : Gravity.TOP) | Gravity.END;
    }
}
