package com.rampey.materialtv;

import android.content.Context;
import android.util.TypedValue;

/**
 * The launcher's one shape scale (Material 3 Expressive), in dp: cards 32, media 20, buttons a full pill. Focused
 * media get a 3 dp ring 6 dp outside the item, concentric with it, while the item grows and its corners tighten.
 */
final class Focus {
    static final int CARD = 32, MEDIA = 20, RING = 6, STROKE = 3;

    private Focus() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
