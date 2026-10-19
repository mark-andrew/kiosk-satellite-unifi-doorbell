// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** SDK 1, overlay. The KS colors, shapes and typeface for native overlays. KS builds it and passes a new one when its theme changes. */
public final class KsTheme {
    /** Supplies the KS typeface at a weight from 100 to 900, or null for the platform default. */
    public interface Fonts {
        Typeface typeface(int weight);
    }

    /** Supplies a Material Design Icon's outline on a 24 by 24 grid, or null for an unknown name. */
    public interface Icons {
        Path path(String name);
    }

    private final boolean dark;
    private final float density;
    private final Map<String, Integer> colors;
    private final Fonts fonts;
    private final Icons icons;

    /** Built by KS. Tests may build one with their own colors. */
    public KsTheme(boolean dark, float density, Map<String, Integer> colors, Fonts fonts) {
        this(dark, density, colors, fonts, null);
    }

    /** Built by KS with its icon set. Tests may pass their own. */
    public KsTheme(boolean dark, float density, Map<String, Integer> colors, Fonts fonts, Icons icons) {
        this.dark = dark;
        this.density = density;
        this.colors = Collections.unmodifiableMap(new HashMap<>(colors));
        this.fonts = fonts;
        this.icons = icons;
    }

    public boolean isDark() { return dark; }

    /** An ARGB color by Material 3 role name, such as surface, onSurface, primary or outlineVariant, plus success. */
    public int color(String role) {
        Integer value = colors.get(role);
        if (value == null) throw new IllegalArgumentException("Unknown color role: " + role);
        return value;
    }

    /** Every color role this KS version provides. */
    public Map<String, Integer> colors() { return colors; }

    /** Pixels for a length in dp. */
    public int px(float dp) { return Math.round(dp * density); }

    /** Corner radius of cards, dialogs and other primary surfaces, in dp. */
    public int radiusCard() { return 24; }
    /** Corner radius of rows and small tappable surfaces inside a card, in dp. */
    public int radiusRow() { return 14; }
    /** Corner radius of text fields and other input controls, in dp. */
    public int radiusControl() { return 12; }
    /** Inner padding of a card, in dp. */
    public int inset() { return 20; }
    /** Gap between cards, in dp. */
    public int cardGap() { return 16; }

    /** The KS typeface at a weight from 100 to 900. */
    public Typeface typeface(int weight) {
        Typeface typeface = fonts == null ? null : fonts.typeface(Math.max(100, Math.min(900, weight)));
        return typeface != null ? typeface : Typeface.DEFAULT;
    }

    /**
     * A Material Design Icon from KS's own set, such as "mdi:garage", painted in a color role. It is 24 dp unless its
     * bounds say otherwise, and setTint recolors it. Null for an unknown name.
     */
    public Drawable icon(String name, String role) {
        Path path = iconPath(name);
        return path == null ? null : new IconDrawable(path, color(role), px(24));
    }

    /** An icon's outline on a 24 by 24 grid, for drawing it yourself. Null for an unknown name. */
    public Path iconPath(String name) {
        if (icons == null || name == null) return null;
        Path path = icons.path(name);
        return path == null ? null : new Path(path);
    }

    /** A card background like the one KS toasts float over the dashboard: surfaceContainerHigh, the card radius and a hairline outline. */
    public GradientDrawable card() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(color("surfaceContainerHigh"));
        card.setCornerRadius(px(radiusCard()));
        card.setStroke(Math.max(1, px(1)), color("outlineVariant"));
        return card;
    }

    /** Style text with the KS typeface at a size in sp, a weight and a color role. */
    public void styleText(TextView view, float sp, int weight, String role) {
        view.setTypeface(typeface(weight));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(role));
    }

    /** Style a TextView or Button as a KS pill button. Filled uses primary, otherwise it is a quiet text button. */
    public void stylePill(TextView view, boolean filled) {
        int text = color(filled ? "onPrimary" : "primary");
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(px(100));
        shape.setColor(color("primary"));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(px(100));
        mask.setColor(Color.WHITE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(31, Color.red(text), Color.green(text), Color.blue(text))), filled ? shape : null, mask));
        styleText(view, 14.5f, 600, filled ? "onPrimary" : "primary");
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(px(20), px(12), px(20), px(12));
        view.setMinWidth(px(96));
        view.setMinimumWidth(px(96));
        view.setMinHeight(px(44));
        view.setMinimumHeight(px(44));
        view.setStateListAnimator(null);
    }

    /** Style a SeekBar like the KS sliders: a 4 dp track filled in primary over surfaceContainerHighest and a 20 dp primary thumb. */
    public void styleSlider(SeekBar view) {
        int track = px(4);
        GradientDrawable empty = new GradientDrawable();
        empty.setCornerRadius(track / 2f);
        empty.setColor(color("surfaceContainerHighest"));
        GradientDrawable filled = new GradientDrawable();
        filled.setCornerRadius(track / 2f);
        filled.setColor(color("primary"));
        LayerDrawable progress = new LayerDrawable(new Drawable[] {empty, new ClipDrawable(filled, Gravity.START, ClipDrawable.HORIZONTAL)});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        for (int layer = 0; layer < 2; layer++) {
            progress.setLayerHeight(layer, track);
            progress.setLayerGravity(layer, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        view.setProgressDrawable(progress);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(color("primary"));
        thumb.setSize(px(20), px(20));
        view.setThumb(thumb);
        view.setSplitTrack(false);
        view.setBackground(null);
        // The thumb's half width on each side, and a 44 dp touch target.
        view.setPadding(px(10), 0, px(10), 0);
        view.setMinimumHeight(px(44));
    }

    /** Fills an icon outline into its bounds, scaled from the 24 by 24 grid. */
    private static final class IconDrawable extends Drawable {
        private final Path path;
        private final Path scaled = new Path();
        private final Matrix matrix = new Matrix();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        IconDrawable(Path path, int color, int size) {
            this.path = path;
            this.size = size;
            paint.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            matrix.setScale(bounds.width() / 24f, bounds.height() / 24f);
            matrix.postTranslate(bounds.left, bounds.top);
            path.transform(matrix, scaled);
            canvas.drawPath(scaled, paint);
        }

        @Override public void setTintList(ColorStateList tint) {
            if (tint != null) { paint.setColor(tint.getColorForState(getState(), tint.getDefaultColor())); invalidateSelf(); }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}
