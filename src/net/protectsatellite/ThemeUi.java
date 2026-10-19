// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

final class ThemeUi {
    static GradientDrawable surface(KsTheme theme, String role, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(theme.color(role)); shape.setCornerRadius(theme.px(radius));
        shape.setStroke(Math.max(1, theme.px(1)), theme.color("outlineVariant")); return shape;
    }
    static void choice(TextView label, KsTheme theme) {
        theme.styleText(label, 16, 400, "onSurface"); label.setBackground(surface(theme, "surfaceContainerHigh", theme.radiusControl()));
        label.setMinHeight(theme.px(52)); label.setPadding(theme.px(12), theme.px(8), theme.px(12), theme.px(8));
    }
}
