package com.rampeys.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

import palettes.TonalPalette;

/**
 * A generated wallpaper in the style of Material You phone wallpapers: a handful of big Material 3 Expressive shapes
 * (circle, cookie, clover, sunny, pill, arch, gem) overlapping in one tonal palette, on a soft gradient. Each scene is
 * built from a hue and a seed, so it is different every time; shapes drift and turn slowly. Paths are built once;
 * each frame only moves and rotates them.
 */
final class ShapeScene extends View {
    private static final int CIRCLE = 0, COOKIE = 1, CLOVER = 2, SUNNY = 3, PILL = 4, ARCH = 5, GEM = 6;

    private static final class Item {
        Path path;
        int color;
        float cx, cy;         // centre, fraction of width/height
        float rot, spin;      // degrees, degrees per second
        float ax, ay, period; // drift amplitude (px) and period (s)
        float phase;
    }

    private final Item[] items;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Paint bg = new Paint();
    private final int top, bottom;
    final int onColor, mutedColor, accentColor;
    private final long born = SystemClock.uptimeMillis();
    private final float density;

    ShapeScene(Context c, double hue, long seed) {
        this(c, hue, 0f, seed);
    }

    /** light: 0 = night (darkest), 1 = midday (brightest background, livelier color). */
    ShapeScene(Context c, double hue, float light, long seed) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        Random r = new Random(seed);
        TonalPalette primary = TonalPalette.fromHueAndChroma(hue, 40 + 16 * light);
        TonalPalette secondary = TonalPalette.fromHueAndChroma((hue + 20) % 360, 24);
        TonalPalette tertiary = TonalPalette.fromHueAndChroma((hue + 60) % 360, 36);
        TonalPalette neutral = TonalPalette.fromHueAndChroma(hue, 8);
        top = neutral.tone(Math.round(12 + 22 * light));
        bottom = neutral.tone(Math.round(5 + 12 * light));
        onColor = neutral.tone(94);
        mutedColor = neutral.tone(80);
        accentColor = primary.tone(80);

        int[] palette = {primary.tone(40), primary.tone(60), primary.tone(80), secondary.tone(50), secondary.tone(70),
                tertiary.tone(50), tertiary.tone(70), primary.tone(30)};
        int n = 7 + r.nextInt(3);
        items = new Item[n];
        int[] kinds = {CIRCLE, COOKIE, CLOVER, SUNNY, PILL, ARCH, GEM};
        for (int i = 0; i < n; i++) {
            Item it = new Item();
            int kind = kinds[r.nextInt(kinds.length)];
            // A few big anchors and several smaller accents, like a Material You wallpaper.
            float size = (i < 2 ? 230 + r.nextInt(110) : 90 + r.nextInt(130)) * density;
            it.path = shape(kind, size, r);
            it.color = palette[r.nextInt(palette.length)];
            // Keep the left third mostly clear for the clock: shapes live right of 40%, some bleed off the edges.
            it.cx = 0.50f + r.nextFloat() * 0.56f;
            it.cy = -0.1f + r.nextFloat() * 1.2f;
            it.rot = r.nextFloat() * 360f;
            it.spin = (r.nextBoolean() ? 1 : -1) * (0.6f + r.nextFloat() * 2.4f);
            it.ax = (10 + r.nextInt(30)) * density;
            it.ay = (10 + r.nextInt(30)) * density;
            it.period = 40 + r.nextInt(50);
            it.phase = r.nextFloat() * 6.28f;
            items[i] = it;
        }
        // Expressive shapes are soft: round every corner.
        paint.setPathEffect(new android.graphics.CornerPathEffect(36 * density));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, w, h, top, bottom, Shader.TileMode.CLAMP));
        // A soft dark fade from the left keeps the clock readable over any shape that drifts behind it.
        scrim.setShader(new LinearGradient(0, 0, w * 0.6f, 0, bottom, bottom & 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bg);
        float t = (SystemClock.uptimeMillis() - born) / 1000f;
        for (Item it : items) {
            float x = it.cx * w + it.ax * (float) Math.sin(6.283f * t / it.period + it.phase);
            float y = it.cy * h + it.ay * (float) Math.cos(6.283f * t / it.period + it.phase);
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(it.rot + it.spin * t);
            paint.setColor(it.color);
            canvas.drawPath(it.path, paint);
            canvas.restore();
        }
        canvas.drawRect(0, 0, w * 0.6f, h, scrim);
    }

    /** Material 3 Expressive-style shapes, centred on the origin. */
    private static Path shape(int kind, float s, Random r) {
        Path p = new Path();
        switch (kind) {
            case CIRCLE:
                p.addCircle(0, 0, s * 0.8f, Path.Direction.CW);
                break;
            case COOKIE:
                polar(p, s, 7 + r.nextInt(6), 0.08f);
                break;
            case CLOVER:
                polar(p, s, 4, 0.28f);
                break;
            case SUNNY:
                polar(p, s, 12 + r.nextInt(6), 0.05f);
                break;
            case PILL: {
                float l = s * 1.6f, wdt = s * 0.62f;
                p.addRoundRect(new RectF(-l / 2, -wdt / 2, l / 2, wdt / 2), wdt / 2, wdt / 2, Path.Direction.CW);
                break;
            }
            case ARCH: {
                float wdt = s * 1.2f, hgt = s * 1.5f, rad = wdt / 2;
                p.moveTo(-rad, hgt / 2);
                p.lineTo(-rad, -hgt / 2 + rad);
                p.arcTo(new RectF(-rad, -hgt / 2, rad, -hgt / 2 + wdt), 180, 180);
                p.lineTo(rad, hgt / 2);
                p.close();
                break;
            }
            default: { // GEM: a soft-cornered hexagon
                float rad = s * 0.85f;
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI / 3 * i;
                    float x = (float) (rad * Math.cos(a)), y = (float) (rad * Math.sin(a));
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                break;
            }
        }
        return p;
    }

    /** A smooth scalloped circle: radius varies with a cosine of the angle (lobes, depth as a fraction). */
    private static void polar(Path p, float s, int lobes, float depth) {
        int steps = 180;
        for (int i = 0; i <= steps; i++) {
            double a = 2 * Math.PI * i / steps;
            float rad = (float) (s * 0.8f * (1 + depth * Math.cos(lobes * a)));
            float x = (float) (rad * Math.cos(a)), y = (float) (rad * Math.sin(a));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
    }
}
