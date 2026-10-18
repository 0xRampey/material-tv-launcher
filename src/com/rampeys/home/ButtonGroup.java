package com.rampeys.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Material 3 Expressive "connected button group": buttons sit edge to edge with a hairline gap, the outer corners are
 * fully round and the inner ones tight. The selected button morphs into a full pill in the accent color; the focused
 * one turns white. Corner changes animate, so selection reads as a shape change, not just a color change.
 */
final class ButtonGroup extends LinearLayout {

    interface Listener {
        void onPick(int index);
    }

    private static final PathInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final List<TextView> buttons = new ArrayList<>();
    private final List<float[]> radii = new ArrayList<>();
    private final boolean[] selectable;
    private int selected = -1;
    private Palette palette;

    ButtonGroup(Context context, Typeface face, Palette palette, String[] labels, int[] icons, boolean[] selectable, Listener listener) {
        super(context);
        this.palette = palette;
        this.selectable = selectable;
        setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = new TextView(context);
            b.setText(labels[i]);
            b.setTypeface(face);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(20), dp(11), dp(22), dp(11));
            b.setFocusable(true);
            b.setClickable(true);
            Drawable icon = context.getDrawable(icons[i]);
            if (icon != null) {
                icon.setBounds(0, 0, dp(18), dp(18));
                b.setCompoundDrawablesRelative(icon, null, null, null);
                b.setCompoundDrawablePadding(dp(8));
            }
            b.setOnClickListener(v -> listener.onPick(index));
            b.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
                restyle(index, true);
            });
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(3));
            buttons.add(b);
            radii.add(targetRadii(index));
            addView(b, lp);
            restyle(index, false);
        }
    }

    void select(int index) {
        int previous = selected;
        selected = index;
        if (previous >= 0) restyle(previous, true);
        if (index >= 0) restyle(index, true);
    }

    void setPalette(Palette p) {
        palette = p;
        for (int i = 0; i < buttons.size(); i++) restyle(i, false);
    }

    private float[] targetRadii(int index) {
        float full = dp(24), tight = dp(8);
        boolean pill = index == selected && selectable[index];
        boolean first = index == 0, last = index == buttons.size() - 1 || index == selectable.length - 1;
        float start = pill || first ? full : tight;
        float end = pill || last ? full : tight;
        // top-left, top-right, bottom-right, bottom-left (each as x, y)
        return new float[]{start, start, end, end, end, end, start, start};
    }

    private void restyle(int index, boolean animate) {
        TextView b = buttons.get(index);
        boolean focused = b.hasFocus();
        boolean on = index == selected && selectable[index];
        int fill = focused ? palette.inverseSurface : on ? palette.primary : withAlpha(Color.WHITE, 0x1A);
        int ink = focused ? palette.inverseOnSurface : on ? palette.onPrimary : palette.onSurface;
        b.setTextColor(ink);
        for (Drawable d : b.getCompoundDrawablesRelative()) if (d != null) d.setTintList(ColorStateList.valueOf(ink));
        float[] from = radii.get(index);
        float[] to = targetRadii(index);
        radii.set(index, to);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        b.setBackground(bg);
        if (!animate) {
            bg.setCornerRadii(to);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.FAST_SPATIAL_MS);
        a.setInterpolator(Motion.FAST_SPATIAL);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            float[] r = new float[8];
            for (int k = 0; k < 8; k++) r[k] = from[k] + (to[k] - from[k]) * t;
            bg.setCornerRadii(r);
        });
        a.start();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    View button(int index) {
        return buttons.get(index);
    }
}
