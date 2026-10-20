package com.rampey.materialtv;

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
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Home, Apps and Settings share one tinted strip. The selected page has an accent-colored pill;
 * the focused tab uses the inverse surface colors. Fills fade as focus and selection change.
 */
final class ButtonGroup extends LinearLayout {

    interface Listener {
        void onPick(int index);
    }

    private final List<TextView> buttons = new ArrayList<>();
    private final List<Integer> fills = new ArrayList<>();
    private final List<ValueAnimator> fillAnimators = new ArrayList<>();
    private ValueAnimator stripAnimator;
    private final boolean[] selectable;
    private int selected = -1;
    private Palette palette;

    ButtonGroup(Context context, Typeface face, Palette palette, String[] labels, int[] icons, boolean[] selectable, Listener listener) {
        super(context);
        this.palette = palette;
        this.selectable = selectable;
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        strip.setCornerRadius(dp(30));
        setBackground(strip);
        setPadding(dp(5), dp(5), dp(5), dp(5));
        styleStrip();
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
            if (i > 0) lp.setMarginStart(dp(4));
            buttons.add(b);
            fills.add(Color.TRANSPARENT);
            fillAnimators.add(null);
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
        if (stripAnimator != null) stripAnimator.cancel();
        int from = strip.getColor().getDefaultColor();
        int to = stripColor();
        stripAnimator = ValueAnimator.ofArgb(from, to).setDuration(Motion.DEFAULT_EFFECTS_MS);
        stripAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        stripAnimator.addUpdateListener(a -> strip.setColor((int) a.getAnimatedValue()));
        stripAnimator.start();
        for (int i = 0; i < buttons.size(); i++) restyle(i, true, true);
    }

    private final GradientDrawable strip = new GradientDrawable();

    private void styleStrip() {
        strip.setColor(stripColor());
    }

    private int stripColor() {
        return (palette.container & 0x00FFFFFF) | 0xE6000000;
    }

    private void restyle(int index, boolean animate) {
        restyle(index, animate, false);
    }

    private void restyle(int index, boolean animate, boolean retint) {
        ValueAnimator previous = fillAnimators.get(index);
        if (previous != null) previous.cancel();
        fillAnimators.set(index, null);
        TextView b = buttons.get(index);
        boolean focused = b.hasFocus();
        boolean on = index == selected && selectable[index];
        int fill = focused ? palette.inverseSurface : on ? palette.primary : Color.TRANSPARENT;
        int ink = focused ? palette.inverseOnSurface : on ? palette.onPrimary : palette.onSurfaceVariant;
        b.setTextColor(ink);
        for (Drawable d : b.getCompoundDrawablesRelative()) if (d != null) d.setTintList(ColorStateList.valueOf(ink));
        GradientDrawable bg = b.getBackground() instanceof GradientDrawable ? (GradientDrawable) b.getBackground() : null;
        if (bg == null) {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(24));
            bg.setColor(fill);
            b.setBackground(bg);
            fills.set(index, fill);
            return;
        }
        int from = fills.get(index);
        if (!animate || from == fill || !isAttachedToWindow()) {
            bg.setColor(fill);
            fills.set(index, fill);
            return;
        }
        final GradientDrawable g = bg;
        ValueAnimator a = ValueAnimator.ofArgb(from, fill)
                .setDuration(retint ? Motion.DEFAULT_EFFECTS_MS : Motion.FAST_EFFECTS_MS);
        a.setInterpolator(retint ? Motion.DEFAULT_EFFECTS : Motion.FAST_EFFECTS);
        a.addUpdateListener(an -> {
            int color = (int) an.getAnimatedValue();
            fills.set(index, color);
            g.setColor(color);
        });
        fillAnimators.set(index, a);
        a.start();
    }

    void stopAnimations() {
        if (stripAnimator != null) {
            stripAnimator.end();
            stripAnimator = null;
        }
        for (int i = 0; i < buttons.size(); i++) {
            ValueAnimator a = fillAnimators.get(i);
            if (a != null) a.end();
            fillAnimators.set(i, null);
            TextView b = buttons.get(i);
            b.animate().cancel();
            b.setScaleX(b.hasFocus() ? 1.05f : 1f);
            b.setScaleY(b.hasFocus() ? 1.05f : 1f);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimations();
        super.onDetachedFromWindow();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    View button(int index) {
        return buttons.get(index);
    }
}
