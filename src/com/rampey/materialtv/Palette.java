package com.rampey.materialtv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.util.List;
import java.util.Map;

import dynamiccolor.DynamicScheme;
import dynamiccolor.MaterialDynamicColors;
import hct.Hct;
import quantize.QuantizerCelebi;
import scheme.SchemeFidelity;
import score.Score;

/**
 * Material 3 dynamic color, using Google's material-color-utilities: the seed comes from an app's banner through the
 * same quantize-and-score step Android uses for wallpapers, and the roles come from a dark Fidelity scheme, which keeps
 * brand colors recognizable (Netflix stays red, Spotify green).
 */
final class Palette {
    static final int DEFAULT_SEED = 0xFF6750A4; // Material baseline purple

    private static final MaterialDynamicColors ROLES = new MaterialDynamicColors();

    final int seed;
    final int surface;          // page background
    final int surfaceBright;    // top of the background gradient
    final int container;        // the dock
    final int containerHigh;    // cards, selected button
    final int primary;          // accents
    final int onPrimary;
    final int primaryContainer; // the large background shape
    final int onSurface;
    final int onSurfaceVariant;
    final int inverseSurface;   // focused fill
    final int inverseOnSurface; // focused ink

    private Palette(int seed) {
        this.seed = seed;
        DynamicScheme s = new SchemeFidelity(Hct.fromInt(seed), true, 0.0);
        surface = ROLES.surfaceDim().getArgb(s);
        surfaceBright = ROLES.surfaceContainerHigh().getArgb(s);
        container = ROLES.surfaceContainer().getArgb(s);
        containerHigh = ROLES.surfaceContainerHighest().getArgb(s);
        primary = ROLES.primary().getArgb(s);
        onPrimary = ROLES.onPrimary().getArgb(s);
        primaryContainer = ROLES.primaryContainer().getArgb(s);
        onSurface = ROLES.onSurface().getArgb(s);
        onSurfaceVariant = ROLES.onSurfaceVariant().getArgb(s);
        inverseSurface = ROLES.inverseSurface().getArgb(s);
        inverseOnSurface = ROLES.inverseOnSurface().getArgb(s);
    }

    static Palette of(int seed) {
        return new Palette(seed);
    }

    /** The best theme color in an app's banner, picked the way Android picks wallpaper colors. */
    static int seedFrom(Drawable art) {
        if (art == null) return DEFAULT_SEED;
        // Sample a copy: changing the bounds of the drawable on screen would shrink the logo in its tile.
        Drawable.ConstantState state = art.getConstantState();
        Drawable copy = state != null ? state.newDrawable().mutate() : null;
        if (copy == null) return DEFAULT_SEED;
        int w = 64, h = 36;
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(small));
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        small.recycle();
        Map<Integer, Integer> colors = QuantizerCelebi.quantize(pixels, 64);
        List<Integer> ranked = Score.score(colors, 1, DEFAULT_SEED, true);
        return ranked.isEmpty() ? DEFAULT_SEED : ranked.get(0);
    }
}
