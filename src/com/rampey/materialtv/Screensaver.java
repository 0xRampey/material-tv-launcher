package com.rampey.materialtv;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


/**
 * The launcher's screensaver: a large clock and date over generated Material You-style wallpapers (ShapeScene).
 * Every hour a new palette and arrangement crossfades in. Shows the song if music plays.
 * The clock block moves a little every minute so nothing sits still on the panel for hours.
 */
public class Screensaver extends DreamService {
    private static final long FRAME_MS = 0;           // every display frame: 60 fps on a 60 Hz TV (owner choice)
    private static final long PALETTE_EVERY = 60 * 60_000L;   // a new wallpaper every hour (owner choice)

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ShapeScene scene;
    private LinearLayout block;
    private TextView clock, date, song;
    private double hue;
    private long seed;
    private long last;
    private boolean running;
    private int minuteShift;

    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (last == 0 || now - last >= FRAME_MS) {
                scene.invalidate();
                last = now;
            }
            root.postOnAnimation(this);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            clock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(d));
            date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d));
            updateSong();
            // Move the clock block slightly each minute (no fixed pixels on the panel for hours).
            int m = (int) (System.currentTimeMillis() / 60_000L);
            if (m != minuteShift) {
                minuteShift = m;
                float dx = dp(((m * 37) % 61) - 30), dy = dp(((m * 53) % 41) - 20);
                block.animate().translationX(dx).translationY(dy).setDuration(2400)
                        .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000) + 5);
        }
    };

    private final Runnable shift = new Runnable() {
        @Override public void run() {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            ShapeScene next = sceneForNow();
            next.setAlpha(0f);
            ShapeScene old = scene;
            root.addView(next, root.indexOfChild(old) + 1,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scene = next;
            next.animate().alpha(1f).setDuration(2400).setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction(() -> root.removeView(old)).start();
            recolorText(next);
            handler.postDelayed(this, PALETTE_EVERY);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        hue = (System.currentTimeMillis() / 1000L) % 360;
        seed = System.currentTimeMillis();
        setContentView(build());
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        last = 0;
        root.postOnAnimation(drift);
        handler.post(tick);
        handler.postDelayed(shift, PALETTE_EVERY);
    }

    @Override public void onDreamingStopped() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        root.removeCallbacks(drift);
        super.onDreamingStopped();
    }

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(new View(this));   // index 0 placeholder so scenes always sit under the clock
        scene = sceneForNow();
        root.addView(scene, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        clock = text(160, scene.onColor, "'wght' 300, 'opsz' 48, 'ROND' 0");
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.01f);
        date = text(30, scene.mutedColor, "'wght' 560, 'opsz' 24, 'ROND' 0");
        date.setPadding(dp(6), dp(4), 0, 0);
        song = text(22, scene.accentColor, "'wght' 560, 'opsz' 24, 'ROND' 0");
        song.setPadding(dp(6), dp(18), 0, 0);
        song.setSingleLine(true);
        song.setMaxWidth(dp(560));
        song.setEllipsize(android.text.TextUtils.TruncateAt.END);
        block.addView(clock);
        block.addView(date);
        block.addView(song);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
        bl.setMargins(dp(96), 0, 0, dp(84));
        root.addView(block, bl);
        return root;
    }

    // Time of day -> hue and brightness. Keyframes: hour, hue (degrees), light (0 night .. 1 midday).
    private static final double[][] SKY = {
            {0, 265, 0.0}, {5, 250, 0.05}, {7, 35, 0.45}, {10, 75, 0.8}, {13, 205, 1.0},
            {16, 190, 0.85}, {18, 25, 0.6}, {19.5, 340, 0.35}, {21, 285, 0.1}, {24, 265, 0.0}};

    /** A new wallpaper for the current time of day, with a little randomness in hue. */
    private ShapeScene sceneForNow() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        double h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0;
        int i = 0;
        while (i < SKY.length - 2 && h >= SKY[i + 1][0]) i++;
        double t = (h - SKY[i][0]) / (SKY[i + 1][0] - SKY[i][0]);
        double from = SKY[i][1], to = SKY[i + 1][1];
        double d = ((to - from + 540) % 360) - 180;            // shortest way around the color wheel
        double jitter = ((seed >>> 33) % 25) - 12;              // +-12 degrees so days differ
        hue = (from + d * t + jitter + 360) % 360;
        float light = (float) (SKY[i][2] + (SKY[i + 1][2] - SKY[i][2]) * t);
        return new ShapeScene(this, hue, light, seed);
    }

    /** Glide the clock's colors to the new wallpaper's palette. */
    private void recolorText(ShapeScene next) {
        int c0 = clock.getCurrentTextColor(), d0 = date.getCurrentTextColor(), s0 = song.getCurrentTextColor();
        android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(2400);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            clock.setTextColor((int) argb.evaluate(t, c0, next.onColor));
            date.setTextColor((int) argb.evaluate(t, d0, next.mutedColor));
            song.setTextColor((int) argb.evaluate(t, s0, next.accentColor));
        });
        a.start();
    }

    /** "♪ Title · Artist" while music plays (needs the Now Playing listener the launcher already uses). */
    private void updateSong() {
        String line = "";
        try {
            MediaSessionManager m = getSystemService(MediaSessionManager.class);
            for (MediaController c : m.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                PlaybackState st = c.getPlaybackState();
                MediaMetadata md = c.getMetadata();
                if (st == null || md == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                CharSequence title = md.getText(MediaMetadata.METADATA_KEY_TITLE);
                CharSequence artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (title == null) continue;
                line = "♪  " + title + (artist != null ? "  ·  " + artist : "");
                break;
            }
        } catch (SecurityException ignored) {
        }
        song.setText(line);
        song.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView text(float sp, int color, String axes) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        try {
            t.setTypeface(new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf").setFontVariationSettings(axes).build());
        } catch (RuntimeException ignored) {
        }
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
