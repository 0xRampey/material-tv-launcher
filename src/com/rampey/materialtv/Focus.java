package com.rampey.materialtv;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * The launcher's one shape + focus system (Material 3 Expressive). Shape scale in dp: cards 32, media 20, buttons a
 * full pill. Media focus: a white 3dp ring 6dp outside the item, concentric with it, while the item grows a little
 * and its corners tighten (the dock tiles do the same).
 */
final class Focus {
    static final int CARD = 32, MEDIA = 20, RING = 6, STROKE = 3;

    private Focus() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /**
     * Wrap a media view (w x h px, its background shape `shape`) in a ring frame that overhangs it by RING on every
     * side, so the layout size stays w x h and the ring draws outside. Returns the frame and its LayoutParams.
     */
    static FrameLayout ring(Context c, View media, GradientDrawable shape, int w, int h) {
        FrameLayout frame = new FrameLayout(c);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        int r = dp(c, RING);
        frame.setPadding(r, r, r, r);
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(shape.getCornerRadius() + r);
        frame.setForeground(ring);
        frame.addView(media, new FrameLayout.LayoutParams(w, h));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w + 2 * r, h + 2 * r);
        lp.setMargins(-r, -r, -r, -r);
        frame.setLayoutParams(lp);
        frame.setTag(R_RING, ring);
        frame.setTag(R_SHAPE, shape);
        return frame;
    }

    static final int R_RING = 0x7f0f0001, R_SHAPE = 0x7f0f0002;

    /** Focus state for a ring frame: ring on/off, grow, corners tighten (concentric ring follows). */
    static void set(Context c, FrameLayout frame, boolean focused, float rest) {
        GradientDrawable ring = (GradientDrawable) frame.getTag(R_RING);
        GradientDrawable shape = (GradientDrawable) frame.getTag(R_SHAPE);
        float target = focused ? rest * 0.62f : rest;
        ObjectAnimator.ofFloat(shape, "cornerRadius", shape.getCornerRadius(), target).setDuration(240).start();
        ring.setCornerRadius(target + dp(c, RING));
        ring.setStroke(focused ? dp(c, STROKE) : 0, Color.WHITE);
        frame.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0.8f)).setDuration(focused ? 300 : 200).start();
    }

    /** Let a container's children grow past its bounds (focus scale) without being cut. */
    static <T extends ViewGroup> T noClip(T g) {
        g.setClipChildren(false);
        g.setClipToPadding(false);
        return g;
    }
}
