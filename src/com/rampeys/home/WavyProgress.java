package com.rampeys.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

/**
 * Material 3 Expressive's wavy linear progress indicator, as Pixel shows for media: the played part is a gently
 * moving wave, the rest a flat track. The wave flattens when playback pauses. Progress is computed from the media
 * session's position and speed, so it advances smoothly between updates. Draws only while shown and playing.
 */
final class WavyProgress extends View {
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final float wavelength, maxAmp, gap;
    private long position, updatedAt, duration;
    private float speed;
    private boolean playing;
    private float amp;          // current amplitude, eases toward maxAmp (playing) or 0 (paused)
    private float phase;
    private long lastFrame;

    WavyProgress(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        wavelength = 22 * d;
        maxAmp = 2.6f * d;
        gap = 5 * d;
        for (Paint p : new Paint[]{active, track}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(3.2f * d);
        }
    }

    void setColors(int activeColor, int trackColor) {
        active.setColor(activeColor);
        track.setColor(trackColor);
        invalidate();
    }

    /** From the media session: position (ms) at updatedAt (elapsedRealtime ms), playback speed, duration (ms). */
    void setState(long position, long updatedAt, float speed, long duration, boolean playing) {
        this.position = position;
        this.updatedAt = updatedAt;
        this.speed = speed;
        this.duration = duration;
        this.playing = playing;
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    private float progress() {
        if (duration <= 0) return 0f;
        long pos = position;
        if (playing && updatedAt > 0) pos += (long) ((SystemClock.elapsedRealtime() - updatedAt) * speed);
        return Math.max(0f, Math.min(1f, pos / (float) duration));
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float target = playing ? maxAmp : 0f;
        amp += (target - amp) * Math.min(1f, dt * 6f);
        if (playing) phase = (phase + dt * 2.4f) % (float) (2 * Math.PI);

        float w = getWidth(), mid = getHeight() / 2f, inset = active.getStrokeWidth() / 2f;
        float end = inset + (w - 2 * inset) * progress();
        wave.reset();
        wave.moveTo(inset, mid);
        for (float x = inset; x <= end; x += 2f) {
            // The wave grows in over the first wavelength, so a just-started song shows a flat line, not a hook.
            float grow = Math.min(1f, (x - inset) / wavelength);
            wave.lineTo(x, mid + amp * grow * (float) Math.sin(2 * Math.PI * (x / wavelength) - phase));
        }
        canvas.drawPath(wave, active);
        if (end + gap < w - inset) canvas.drawLine(end + gap, mid, w - inset, mid, track);

        if ((playing || Math.abs(amp - target) > 0.05f) && isShown()) postInvalidateOnAnimation();
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
