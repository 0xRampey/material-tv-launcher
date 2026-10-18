package com.rampeys.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Material 3 Expressive's scalloped shapes ("cookie" and friends): a circle with soft, evenly spaced bumps.
 * It can morph from one shape to another by blending their outlines point by point.
 */
final class CookieDrawable extends Drawable {
    private static final int STEPS = 240;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int fromLobes, toLobes;
    private float fromDepth, toDepth;
    private float t = 1f;

    CookieDrawable(int color, int lobes, float depth) {
        paint.setColor(color);
        fromLobes = toLobes = lobes;
        fromDepth = toDepth = depth;
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    /** Start a morph to a new shape; drive it with {@link #setProgress}. */
    void morphTo(int lobes, float depth) {
        // Begin from wherever the current morph has reached.
        fromLobes = t < 0.5f ? fromLobes : toLobes;
        fromDepth = fromDepth + (toDepth - fromDepth) * t;
        toLobes = lobes;
        toDepth = depth;
        t = 0f;
    }

    void setProgress(float progress) {
        t = progress;
        rebuild(getBounds());
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect b) {
        rebuild(b);
    }

    private void rebuild(Rect b) {
        path.reset();
        if (b.isEmpty()) return;
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float maxDepth = Math.max(fromDepth, toDepth);
        float r = Math.min(b.width(), b.height()) / 2f / (1f + maxDepth);
        for (int i = 0; i <= STEPS; i++) {
            double a = 2 * Math.PI * i / STEPS;
            float r1 = r * (1f + fromDepth * (float) Math.cos(fromLobes * a));
            float r2 = r * (1f + toDepth * (float) Math.cos(toLobes * a));
            float rr = r1 + (r2 - r1) * t;
            float x = cx + rr * (float) Math.cos(a);
            float y = cy + rr * (float) Math.sin(a);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
