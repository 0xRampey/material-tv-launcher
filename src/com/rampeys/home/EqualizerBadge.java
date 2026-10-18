package com.rampeys.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** A tiny animated equalizer on the dock tile of the app that's playing. Animates only while shown. */
final class EqualizerBadge extends View {
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private final long born = SystemClock.uptimeMillis();

    EqualizerBadge(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        pill.setColor(0xD9000000);
        bar.setColor(0xFFFFFFFF);
    }

    void setColors(int background, int bars) {
        pill.setColor(background);
        bar.setColor(bars);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, h / 2, h / 2, pill);
        float t = (SystemClock.uptimeMillis() - born) / 1000f;
        float bw = 3.2f * d, gap = 2.6f * d, maxH = h * 0.56f;
        float x = (w - (3 * bw + 2 * gap)) / 2f, base = h / 2f + maxH / 2f;
        float[] speed = {7.1f, 5.3f, 8.7f}, offset = {0f, 1.7f, 3.1f};
        for (int i = 0; i < 3; i++) {
            float level = 0.3f + 0.7f * Math.abs((float) Math.sin(t * speed[i] / 2f + offset[i]));
            r.set(x, base - maxH * level, x + bw, base);
            canvas.drawRoundRect(r, bw / 2, bw / 2, bar);
            x += bw + gap;
        }
        if (isShown()) postInvalidateOnAnimation();
    }
}
