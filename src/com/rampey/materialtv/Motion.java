package com.rampey.materialtv;

import android.view.animation.PathInterpolator;

/**
 * Material 3 Expressive motion. Expressive replaces easing+duration with springs; these are the spec's cross-platform
 * fallback curves (m3.material.io/styles/motion). Spatial curves overshoot a little and settle (movement, scale,
 * shape); effects curves never overshoot (color, opacity). Fast is for small components, default for larger ones.
 */
final class Motion {
    private Motion() {
    }

    static final PathInterpolator FAST_SPATIAL = new PathInterpolator(0.42f, 1.67f, 0.21f, 0.90f);
    static final long FAST_SPATIAL_MS = 350;
    static final PathInterpolator DEFAULT_SPATIAL = new PathInterpolator(0.38f, 1.21f, 0.22f, 1.00f);
    static final long DEFAULT_SPATIAL_MS = 500;
    static final PathInterpolator FAST_EFFECTS = new PathInterpolator(0.31f, 0.94f, 0.34f, 1.00f);
    static final long FAST_EFFECTS_MS = 150;
    static final PathInterpolator DEFAULT_EFFECTS = new PathInterpolator(0.34f, 0.80f, 0.34f, 1.00f);
    static final long DEFAULT_EFFECTS_MS = 200;
}
