package com.rampey.materialtv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Ask's voice orb (Material 3 Expressive): a shape from the Material shape set, morphing by state, in the primary
 * color over a primary-container halo, with four soft pill bars in the middle (ours, not a borrowed sparkle): they
 * rest when idle, follow your voice while listening and ripple while thinking. Idle is a soft four-leaf clover turning
 * slowly; listening is a nine-scallop cookie whose scallops deepen with your voice; thinking is a soft seven-petal
 * flower spinning faster. Shapes blend into each other (never a seam), and the halo always clears the deepest scallop.
 * Drawn on display frames, only while on screen.
 */
final class VoiceOrb extends View {
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, WARMING = 3;

    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float[] BAR_SHAPE = {0.6f, 1f, 1f, 0.6f};   // resting heights: four soft pills
    private final float[] barNow = {0.6f, 1f, 1f, 0.6f};
    private final long born = System.nanoTime();
    private final Path path = new Path();
    private int mode = IDLE;
    private float target, level, phase, haloScale = 1f, dim = 1f;
    // The shape in use (lobes, depth) and the one it is leaving, blended by morph (0 = old, 1 = new).
    private int lobes = 4, oldLobes = 4;
    private float depth = 0.10f, oldDepth = 0.10f, morph = 1f;
    private long last;

    VoiceOrb(Context c) {
        super(c);
    }

    void setColors(int primary, int primaryContainer, int onPrimary, Drawable sparkle) {
        blob.setColor(primary);
        halo.setColor(primaryContainer);
        halo.setAlpha(110);
        bars.setColor(onPrimary);
        invalidate();
    }

    void setMode(int m) {
        if (m != mode) {
            oldLobes = lobes;
            oldDepth = depth;
            morph = 0f;
            lobes = m == THINKING ? 5 : m == LISTENING || m == WARMING ? 6 : 4;
        }
        mode = m;
        if (m != LISTENING) target = 0f;
        kick();
    }

    /** Speech loudness from the recognizer (dB, roughly -2..10). */
    void setLevel(float db) {
        target = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
        kick();
    }

    private void kick() {
        last = 0;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        // Ease toward the target (fast up, slower down) so the edge follows the voice without jitter.
        level += (target - level) * Math.min(1f, dt * (target > level ? 18f : 6f));
        float wantDepth = mode == THINKING ? 0.045f : mode == WARMING ? 0.02f : mode == LISTENING ? 0.025f + level * 0.035f : 0.04f;
        depth += (wantDepth - depth) * Math.min(1f, dt * 6f);
        morph += (1f - morph) * Math.min(1f, dt * 5f);
        phase += dt * (mode == THINKING ? 2.4f : mode == WARMING ? 0.15f : mode == LISTENING ? 0.5f + level * 1.5f : 0.25f);
        float wantHalo = 1f + (mode == LISTENING ? 0.06f + level * 0.22f : mode == THINKING ? 0.10f : mode == WARMING ? -0.06f : 0f);
        haloScale += (wantHalo - haloScale) * Math.min(1f, dt * 8f);

        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy);
        float r = maxR / 1.6f;                        // the deepest scallops (r x 1.125) and the loudest halo stay inside
        canvas.drawCircle(cx, cy, Math.min(maxR, r * 1.24f * haloScale), halo);

        float wantDim = mode == WARMING ? 0.45f : 1f;
        dim += (wantDim - dim) * Math.min(1f, dt * 10f);
        blob.setAlpha(Math.round(255 * dim));
        path.reset();
        int steps = 240;
        for (int i = 0; i <= steps; i++) {
            double a = 2 * Math.PI * i / steps;
            // Rounded scallops: a cosine lobe shaped toward flat tops (Material shapes are soft, not spiky).
            float newR = scallop(a, lobes, depth);
            float oldR = scallop(a, oldLobes, oldDepth);
            float rr = r * (oldR + (newR - oldR) * morph);
            float x = cx + rr * (float) Math.cos(a), y = cy + rr * (float) Math.sin(a);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.save();
        canvas.rotate(phase * 10f, cx, cy);
        canvas.drawPath(path, blob);
        canvas.restore();

        // The waveform: five rounded bars. Each eases toward its target so the motion stays smooth.
        float secs = (now - born) / 1e9f;
        float bw = r * 0.17f, gap = r * 0.10f, total = 4 * bw + 3 * gap, x0 = cx - total / 2f;
        float maxH = r * 0.52f;
        boolean bounce = false;
        for (int i = 0; i < 4; i++) {
            float want;
            if (mode == LISTENING) {
                float wiggle = 0.7f + 0.3f * (float) Math.sin(secs * 7f + i * 1.9f);
                want = BAR_SHAPE[i] * (0.55f + 0.45f * level * wiggle);
            } else if (mode == THINKING) {
                want = 0.45f + 0.35f * (0.5f + 0.5f * (float) Math.sin(secs * 5f - i * 1.1f));
            } else {
                want = BAR_SHAPE[i] * 0.6f;
            }
            barNow[i] += (want - barNow[i]) * Math.min(1f, dt * 14f);
            bounce |= Math.abs(want - barNow[i]) > 0.002f;
            float bh = Math.max(bw, maxH * barNow[i]);
            float bx = x0 + i * (bw + gap);
            bars.setAlpha(Math.round(255 * dim));
            canvas.drawRoundRect(bx, cy - bh / 2f, bx + bw, cy + bh / 2f, bw / 2f, bw / 2f, bars);
        }
        boolean settling = Math.abs(target - level) > 0.002f || Math.abs(wantHalo - haloScale) > 0.002f
                || Math.abs(wantDepth - depth) > 0.002f || morph < 0.995f || bounce;
        if (isShown() && (mode != IDLE || settling)) postInvalidateOnAnimation();
    }

    /** Radius factor of a soft n-lobed shape at angle a: 1 + depth x a rounded cosine (flattened peaks, soft valleys). */
    private static float scallop(double a, int n, float depth) {
        double c = Math.cos(n * a);
        double soft = Math.signum(c) * Math.pow(Math.abs(c), 1.3);   // > 1 rounds the lobes off (no points)
        return 1f + depth * (float) soft;
    }
}
