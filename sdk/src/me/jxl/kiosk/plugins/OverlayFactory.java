// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

import android.content.Context;
import android.view.View;

/** SDK 1, overlay. Builds the view of one native overlay. Every method runs on the Android main thread. */
public interface OverlayFactory {
    /** Build the view. KS may call this again for the same overlay, for example after its Activity restarts. */
    View create(Context context, KsTheme theme);
    /** The KS theme changed, for example from light to dark. Restyle the view. */
    default void onThemeChanged(View view, KsTheme theme) {}
    /** KS removed the view. Stop its animations and drop references to it. */
    default void onDestroy(View view) {}
}
