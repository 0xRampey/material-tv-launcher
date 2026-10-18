package com.rampeys.home;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Material 3 Expressive home screen for TV: the background and accents take their color from the app in focus,
 * one dock of favorites, everything else on an Apps page. No network, no tracking.
 */
public class HomeActivity extends Activity {

    /** Favorites, left to right. Apps that are missing or disabled are skipped. */

    private static final int SIDE = 48, DOCK_PAD = 8, SCROLL_PAD = 18, GAP = 14, RING = 6, APP_MARGIN = 9;

    /** Material's "emphasized decelerate" curve for things arriving on screen. */
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> seedCache = new ConcurrentHashMap<>();
    private final Map<Integer, Palette> paletteCache = new ConcurrentHashMap<>();
    private final Map<Integer, Typeface> titleFonts = new HashMap<>();
    private final Map<Integer, Typeface> fonts = new HashMap<>();
    private PackageManager pm;
    private Palette palette = Palette.of(Palette.DEFAULT_SEED);

    private GradientDrawable backdrop;
    private CookieDrawable cookie;
    private ImageView shape;
    /**
     * The background shape drifts at 3 degrees a second, on every display frame (60 fps on a 60 Hz TV). It only runs
     * while Home is on screen; the shape is a hardware layer, so each frame just rotates a texture.
     */
    private long driftLast;
    private static final long DRIFT_FRAME_MS = 0;   // every display frame (60 fps on a 60 Hz TV)
    private boolean drifting;
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!drifting) return;
            long now = android.os.SystemClock.uptimeMillis();
            // Every display frame: 60 fps on a 60 Hz TV (owner choice; ~50% of one core, only while Home is up).
            if (driftLast == 0 || now - driftLast >= DRIFT_FRAME_MS) {
                if (driftLast > 0) shape.setRotation((shape.getRotation() + 0.003f * (now - driftLast)) % 360f);
                driftLast = now;
            }
            shape.postOnAnimation(this);
        }
    };
    private final Runnable prebuildApps = new Runnable() {
        @Override public void run() {
            if (!HomeActivity.this.showingApps) fillApps();
        }
    };
    private GradientDrawable dockShape;
    private View homePage;
    private ScrollView appsPage;
    private GridLayout appsGrid;
    private LinearLayout dock;
    private TextView clock;
    private TextView date;
    private TextView focusTitle;
    private ButtonGroup nav;

    // Focus settles before the expensive part runs, so holding a direction stays smooth.
    private String pendingPkg;
    private CharSequence pendingLabel;
    private Drawable pendingArt;
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            applyFocus(pendingPkg, pendingLabel, pendingArt);
        }
    };

    // Now Playing.
    private MediaSessionManager sessions;
    private ComponentName mediaListener;
    private MediaController playing;
    private View nowCard;
    private ImageView nowArt;
    private TextView nowTitle;
    private TextView nowArtist;
    private WavyProgress nowProgress;
    private final Map<String, EqualizerBadge> dockBadges = new HashMap<>();
    private final Map<String, View> deviceDots = new HashMap<>();   // input id -> "on" dot
    private final android.media.tv.TvInputManager.TvInputCallback inputCallback = new android.media.tv.TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) {
            showDeviceState(inputId, state);
        }
    };
    private ImageView nowButton;
    private GradientDrawable nowShape;
    private GradientDrawable nowButtonShape;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            showNowPlaying();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            showNowPlaying();
        }

        @Override public void onSessionDestroyed() {
            pickSession();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickSession();
    private boolean showingApps;
    private String appsKey = "";
    private String dockKey = "";
    private ValueAnimator colorAnimator;
    private final Map<Integer, Typeface> clockFonts = new HashMap<>();

    /** Clock weight through the day: 250 at night, rising to 430 by mid-morning, easing back after sunset. */
    private static int clockWeight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        float h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        float[][] keys = {{0, 250}, {5, 250}, {9, 430}, {17, 430}, {21, 300}, {24, 250}};
        for (int i = 0; i < keys.length - 1; i++) {
            if (h >= keys[i][0] && h <= keys[i + 1][0]) {
                float t = (h - keys[i][0]) / (keys[i + 1][0] - keys[i][0]);
                return Math.round(keys[i][1] + (keys[i + 1][1] - keys[i][1]) * t);
            }
        }
        return 300;
    }

    private Typeface clockFont(int weight) {
        int w = Math.round(weight / 10f) * 10;
        Typeface t = clockFonts.get(w);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + w + ", 'opsz' 48, 'ROND' 0").build();
            } catch (RuntimeException e) {
                t = font(300);
            }
            clockFonts.put(w, t);
        }
        return t;
    }

    /** A new minute: the old time lifts away, the new one settles in with a short weight swell. */
    private void changeClock(String time) {
        int base = clockWeight();
        clock.animate().alpha(0f).translationY(-dp(6)).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                .withEndAction(() -> {
                    clock.setText(time);
                    clock.setTranslationY(dp(8));
                    clock.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL)
                            .setDuration(Motion.DEFAULT_SPATIAL_MS).start();
                    ValueAnimator swell = ValueAnimator.ofInt(base + 320, base).setDuration(700);
                    swell.setInterpolator(Motion.DEFAULT_SPATIAL);
                    swell.addUpdateListener(a -> clock.setTypeface(clockFont((int) a.getAnimatedValue())));
                    swell.start();
                }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
            if (clock.getText().length() == 0) {
                clock.setText(time);
                clock.setTypeface(clockFont(clockWeight()));
            } else if (!time.contentEquals(clock.getText())) {
                changeClock(time);
            }
            date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
            handler.postDelayed(this, 15_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        setContentView(buildRoot());
    }

    @Override protected void onResume() {
        super.onResume();
        fillDock();
        if (showingApps) fillApps();
        handler.removeCallbacks(tick);
        handler.post(tick);
        drifting = true;
        driftLast = 0;
        shape.removeCallbacks(drift);
        shape.postOnAnimation(drift);
        // Build the Apps page quietly once Home is showing, so opening it later is instant.
        handler.removeCallbacks(prebuildApps);
        handler.postDelayed(prebuildApps, 1500);
        startNowPlaying();
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) {
            inputs.registerCallback(inputCallback, handler);
            for (String id : deviceDots.keySet()) showDeviceState(id, inputs.getInputState(id));
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        // Nothing animates while another app is on screen: no CPU or GPU use in the background.
        drifting = false;
        shape.removeCallbacks(drift);
        handler.removeCallbacks(prebuildApps);
        if (colorAnimator != null) colorAnimator.end();
        handler.removeCallbacks(settle);
        stopNowPlaying();
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) inputs.unregisterCallback(inputCallback);
        hideLoader();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showHome();
    }


    @Override public void onBackPressed() {
        if (showingApps) showHome();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (showingApps) showHome(); else showApps();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- layout ----

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{palette.surfaceBright, palette.surface});
        root.setBackground(backdrop);

        // One Expressive "cookie" shape behind everything, drifting slowly and turning a little more when focus
        // moves. It sits on its own GPU layer, so each step only moves the layer; nothing is redrawn.
        cookie = new CookieDrawable(palette.primaryContainer, 9, 0.08f);
        ImageView big = new ImageView(this);
        big.setImageDrawable(cookie);
        big.setAlpha(0.55f);
        big.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        FrameLayout.LayoutParams bigLp = new FrameLayout.LayoutParams(dp(560), dp(560), Gravity.END | Gravity.TOP);
        bigLp.setMargins(0, -dp(150), -dp(120), 0);
        root.addView(big, bigLp);
        shape = big;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(SIDE), dp(32), dp(SIDE), dp(28));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, match());

        nowCard = buildNowPlaying();
        FrameLayout.LayoutParams nowLp = new FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        nowLp.setMargins(0, dp(140), dp(SIDE), 0);
        root.addView(nowCard, nowLp);

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);
        column.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        homePage = buildHomePage();
        pages.addView(homePage, match());

        appsPage = new ScrollView(this);
        appsPage.setVerticalScrollBarEnabled(false);
        appsPage.setClipToPadding(false);
        appsPage.setClipChildren(false);
        LinearLayout appsColumn = new LinearLayout(this);
        appsColumn.setOrientation(LinearLayout.VERTICAL);
        appsColumn.setClipChildren(false);
        TextView appsTitle = text("All apps", 34, palette.onSurface, 760);
        appsTitle.setPadding(dp(4), dp(28), 0, dp(8));
        appsColumn.addView(appsTitle);
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(5);
        appsGrid.setClipChildren(false);
        appsGrid.setPadding(0, dp(12), 0, dp(32));
        appsColumn.addView(appsGrid);
        appsPage.addView(appsColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appsPage.setVisibility(View.GONE);
        pages.addView(appsPage, match());
        return root;
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        // Room for a focused button to grow without its edge being cut off.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(0, dp(4), dp(6), dp(4));

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 46, palette.onSurface, 300);
        clock.setIncludeFontPadding(false);
        when.addView(clock);
        date = text("", 15, palette.onSurfaceVariant, 500);
        date.setPadding(dp(2), dp(2), 0, 0);
        when.addView(date);
        bar.addView(when, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));

        nav = new ButtonGroup(this, font(600), palette,
                new String[]{"Home", "Apps", "Settings"},
                new int[]{R.drawable.ic_home, R.drawable.ic_apps, R.drawable.ic_settings},
                new boolean[]{true, true, false},
                index -> {
                    if (index == 0) showHome();
                    else if (index == 1) showApps();
                    else launch(new Intent(Settings.ACTION_SETTINGS));
                });
        nav.select(0);
        bar.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        return bar;
    }

    private View buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.BOTTOM);
        page.setClipChildren(false);

        focusTitle = text("", 64, palette.onSurface, 820);
        focusTitle.setIncludeFontPadding(false);
        // Normal spacing and no ligatures: at display sizes "tf" or "fl" would otherwise touch.
        focusTitle.setLetterSpacing(0.005f);
        focusTitle.setFontFeatureSettings("'liga' 0, 'clig' 0, 'dlig' 0");
        page.addView(focusTitle);
        Space gap = new Space(this);
        page.addView(gap, new LinearLayout.LayoutParams(1, dp(28)));

        // The dock: a rounded tonal container holding the favorites, like Material's floating toolbar.
        FrameLayout dockFrame = new FrameLayout(this);
        dockFrame.setClipChildren(false);
        dockShape = new GradientDrawable();
        dockShape.setCornerRadius(dp(40));
        dockShape.setColor(withAlpha(palette.container, 0xB3));
        dockFrame.setBackground(dockShape);
        dockFrame.setPadding(dp(DOCK_PAD), dp(6), dp(DOCK_PAD), dp(6));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(dp(SCROLL_PAD), dp(20), dp(SCROLL_PAD), dp(16));
        dock = new LinearLayout(this);
        dock.setClipChildren(false);
        scroller.addView(dock);
        dockFrame.addView(scroller);
        page.addView(dockFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    // ---- content ----

    private static final int DOCK_SIZE = 6;
    /** Never in the dock: settings, the stock home screen and system helpers. */
    private static final java.util.Set<String> NOT_IN_DOCK = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
            "com.google.android.katniss", "com.android.vending", "com.android.systemui"));

    /** The dock: most-used apps of the last 14 days (left = most used), topped up from the TV's other installed apps. */
    private List<String> dockApps() {
        List<String> out = new ArrayList<>();
        // Screensaver apps rack up "use" while the screensaver runs: they never count toward the dock.
        java.util.Set<String> screensavers = new java.util.HashSet<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.service.dreams.DreamService"), 0))
            screensavers.add(r.serviceInfo.packageName);
        try {
            android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> used = new ArrayList<>(usm.queryAndAggregateUsageStats(now - 14L * 86_400_000L, now).values());
            used.sort((a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
            for (android.app.usage.UsageStats u : used) {
                if (out.size() >= DOCK_SIZE || u.getTotalTimeInForeground() < 60_000) break;
                String pkg = u.getPackageName();
                if (pkg.equals(getPackageName()) || NOT_IN_DOCK.contains(pkg) || screensavers.contains(pkg) || launchIntent(pkg) == null) continue;
                out.add(pkg);
            }
        } catch (RuntimeException ignored) {
            // No usage access: the dock fills from installed apps below.
        }
        // HDMI devices that name themselves (CEC), e.g. "PlayStation 5", take the TV input app's place.
        String inputApp = inputApp();
        List<String> devices = cecDevices();
        int at = inputApp == null ? -1 : out.indexOf(inputApp);
        if (at >= 0 && !devices.isEmpty()) {
            out.remove(at);
            out.addAll(at, devices);
            while (out.size() > DOCK_SIZE) out.remove(out.size() - 1);
        }
        // Top up from this TV's own installed apps: most recently used first, then the newest installs.
        if (out.size() < DOCK_SIZE) {
            java.util.Map<String, Long> lastUsed = new java.util.HashMap<>();
            try {
                android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
                long now = System.currentTimeMillis();
                for (android.app.usage.UsageStats u : usm.queryAndAggregateUsageStats(now - 90L * 86_400_000L, now).values())
                    lastUsed.put(u.getPackageName(), u.getLastTimeUsed());
            } catch (RuntimeException ignored) {
            }
            List<String> rest = new ArrayList<>();
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = info.activityInfo.packageName;
                if (!out.contains(pkg) && !rest.contains(pkg) && !pkg.equals(getPackageName()) && !NOT_IN_DOCK.contains(pkg)
                        && !screensavers.contains(pkg)) rest.add(pkg);
            }
            rest.sort((a, b) -> {
                int byUse = Long.compare(lastUsed.getOrDefault(b, 0L), lastUsed.getOrDefault(a, 0L));
                return byUse != 0 ? byUse : Long.compare(installTime(b), installTime(a));
            });
            for (String pkg : rest) {
                if (out.size() >= DOCK_SIZE) break;
                out.add(pkg);
            }
        }
        return out;
    }

    /** CEC-named HDMI devices: dock key "input:<id>" -> {label, parent input id}. */
    private final Map<String, String[]> inputDevices = new HashMap<>();

    private List<String> cecDevices() {
        List<String> keys = new ArrayList<>();
        inputDevices.clear();
        try {
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim == null) return keys;
            for (android.media.tv.TvInputInfo in : tim.getTvInputList()) {
                if (in.getType() != android.media.tv.TvInputInfo.TYPE_HDMI || in.getParentId() == null) continue;
                CharSequence label = in.loadLabel(this);
                if (label == null || label.length() == 0) continue;
                String key = "input:" + in.getId();
                inputDevices.put(key, new String[]{label.toString(), in.getParentId()});
                // Consoles first.
                if (isConsole(label.toString())) keys.add(0, key); else keys.add(key);
            }
        } catch (RuntimeException ignored) {
        }
        return keys;
    }

    /** The app that plays HDMI inputs (the one a passthrough link opens), e.g. "Live TV". */
    private String inputApp() {
        ResolveInfo r = pm.resolveActivity(new Intent(Intent.ACTION_VIEW,
                android.media.tv.TvContract.buildChannelUriForPassthroughInput("x")), 0);
        return r != null && r.activityInfo != null ? r.activityInfo.packageName : null;
    }

    private static boolean isConsole(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        return l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*") || l.contains("xbox")
                || l.contains("nintendo") || l.contains("switch");
    }

    /**
     * A 16:9 banner for an HDMI device, Material You themed-icon style: a deep tone of the console's brand hue with a
     * large Material icon in a light tone of the same hue. No text (the name sits under the tile) and no logos.
     */
    private Drawable inputBanner(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int brand = 0xFF5F6368;
        if (l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*")) brand = 0xFF0047AB;
        else if (l.contains("xbox")) brand = 0xFF107C10;
        else if (l.contains("nintendo") || l.contains("switch")) brand = 0xFFE60012;
        palettes.TonalPalette tones = palettes.TonalPalette.fromInt(brand);
        int icon = isConsole(label) ? R.drawable.ic_gamepad : R.drawable.ic_input_tv;
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(tones.tone(30));
        Drawable d = getDrawable(icon).mutate();
        d.setTint(tones.tone(90));
        int size = 250;
        d.setBounds((640 - size) / 2, (360 - size) / 2, (640 + size) / 2, (360 + size) / 2);
        d.draw(c);
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    private Intent dockIntent(String key) {
        String[] dev = inputDevices.get(key);
        if (dev != null) return new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(dev[1]));
        return launchIntent(key);
    }

    private long installTime(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void fillDock() {
        List<String> present = dockApps();
        // Kept between visits; rebuild only when the order of the dock changes.
        String key = TextUtils.join(",", present);
        if (key.equals(dockKey) && dock.getChildCount() > 0) {
            if (!showingApps) dock.getChildAt(0).requestFocus();
            return;
        }
        dockKey = key;
        dock.removeAllViews();
        dockBadges.clear();
        deviceDots.clear();
        warmPalettes(present);
        int n = Math.max(1, present.size());
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * (SIDE + DOCK_PAD + SCROLL_PAD) - (n - 1) * GAP - n * 2 * RING;
        int tileDp = (int) Math.max(96, Math.min(220, inner / n));
        int i = 0;
        for (String pkg : present) {
            Intent intent = dockIntent(pkg);
            View tile = tile(pkg, intent, tileDp, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(GAP));
            dock.addView(tile, lp);
            arrive(tile, i++);
        }
        if (!showingApps && dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        updateBadges();
    }

    private void fillApps() {
        Set<String> seen = new LinkedHashSet<>();
        List<ResolveInfo> found = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                found.add(info);
            }
        }
        // The grid is kept between visits; rebuild only when the set of installed apps changed.
        String key = TextUtils.join(",", seen);
        if (key.equals(appsKey) && appsGrid.getChildCount() > 0) return;
        appsKey = key;
        appsGrid.removeAllViews();
        // Read each label once (loading a label is slow), then sort.
        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo info : found) labels.put(info, String.valueOf(info.loadLabel(pm)));
        Collections.sort(found, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        // Five columns that exactly fill the width between the side margins (tile + focus-ring room + gaps).
        int cols = appsGrid.getColumnCount();
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * SIDE - cols * (2 * RING + 2 * APP_MARGIN);
        int tileDp = (int) Math.min(170, inner / cols);
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            Intent intent = launchIntent(pkg);
            if (intent == null) continue;
            View tile = tile(pkg, intent, tileDp, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(APP_MARGIN), dp(10), dp(APP_MARGIN), dp(16));
            appsGrid.addView(tile, lp);
        }
    }

    /** A 16:9 card: the app's TV banner, or its icon on a tonal card, with the name underneath. */
    private View tile(String pkg, Intent intent, int widthDp, boolean inDock) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        CharSequence name = pkg;
        Drawable banner = null;
        Drawable icon = null;
        String[] device = inputDevices.get(pkg);
        if (device != null) {
            name = device[0];
            banner = inputBanner(device[0]);
        } else try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            name = pm.getApplicationLabel(ai);
            banner = pm.getApplicationBanner(ai);
            if (banner == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        final CharSequence label = name;
        final Drawable art = banner != null ? banner : icon;

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        ImageView image = new ImageView(this);
        if (banner != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            card.addView(image, match());
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(icon);
            card.addView(image, new FrameLayout.LayoutParams(h / 2, h / 2, Gravity.CENTER));
        }
        card.setContentDescription(label);

        // Material focus: the card grows with a little spring, its corners tighten, and a ring in the
        // accent color appears just outside it. The whole screen re-tints to the app's color.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);   // the ring sits in the gap around the card; badges draw above it
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));

        // Expressive loading indicator: a small shape that morphs while the app takes its time to open.
        CookieDrawable loaderShape = new CookieDrawable(palette.primary, 4, 0.18f);
        ImageView loader = new ImageView(this);
        loader.setImageDrawable(loaderShape);
        loader.setVisibility(View.GONE);
        ringFrame.addView(loader, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        card.setOnClickListener(v -> pressAndLaunch(ringFrame, loader, loaderShape, intent));

        TextView caption = text(label, inDock ? 15 : 14, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(10), 0, 0);
        caption.setAlpha(inDock ? 0f : 0.85f);

        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            // A glow in the app's own color (TV focus guidance) instead of a dark shadow.
            card.setOutlineSpotShadowColor(palette.primary);
            card.setOutlineAmbientShadowColor(palette.primary);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.DEFAULT_EFFECTS_MS).start();
            ObjectAnimator corners = ObjectAnimator.ofFloat(cardShape, "cornerRadius", cardShape.getCornerRadius(), focused ? radius * 0.62f : radius)
                    .setDuration(Motion.FAST_SPATIAL_MS);
            corners.setInterpolator(Motion.FAST_SPATIAL);
            corners.start();
            ring.setCornerRadius((focused ? radius * 0.62f : radius) + dp(RING));
            fadeRing(ring, focused);
            if (inDock) shiftNeighbours(holder, focused);
            caption.animate().cancel();
            if (inDock && focused) {
                caption.setVisibility(View.VISIBLE);
                caption.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            } else if (inDock) {
                caption.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> caption.setVisibility(View.GONE)).start();
            } else {
                caption.animate().alpha(focused ? 1f : 0.85f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            }
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
            if (focused) onTileFocused(pkg, label, art);
        });

        // Status lives under the tile, never on the artwork: an equalizer for the app that's playing, a green dot for
        // an HDMI device that's on. Focused, the name appears beside it.
        LinearLayout captionRow = new LinearLayout(this);
        captionRow.setOrientation(LinearLayout.HORIZONTAL);
        captionRow.setGravity(Gravity.CENTER);
        captionRow.setClipChildren(false);
        caption.setPadding(0, 0, 0, 0);
        caption.setMaxWidth(w + dp(48) - dp(30));
        if (inDock) caption.setVisibility(View.GONE);
        if (inDock && device != null) {
            View dot = new View(this);
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(0xFF6DD58C);                 // Material green, tone 80: "on"
            dot.setBackground(dotShape);
            dot.setVisibility(View.GONE);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
            dl.setMarginEnd(dp(8));
            captionRow.addView(dot, dl);
            String inputId = pkg.substring("input:".length());
            deviceDots.put(inputId, dot);
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim != null) showDeviceState(inputId, tim.getInputState(inputId));
        }
        if (inDock) {
            EqualizerBadge badge = new EqualizerBadge(this);
            badge.setVisibility(View.GONE);
            badge.setColors(0x00000000, 0xFFE6E1E5);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(18), dp(22));
            bl.setMarginEnd(dp(6));
            captionRow.addView(badge, bl);
            dockBadges.put(pkg, badge);
        }
        captionRow.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        // Fixed height so the dock never jumps; a little wider than the tile (negative margins keep the spacing) so a
        // name with a status mark beside it still fits.
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w + dp(48), dp(32));
        rl.topMargin = dp(8);
        rl.leftMargin = -dp(24);
        rl.rightMargin = -dp(24);
        holder.addView(captionRow, rl);
        return holder;
    }

    /** The focus ring fades in (fast effects) in the palette's focus color instead of snapping on. */
    private void fadeRing(GradientDrawable ring, boolean focused) {
        int color = palette.inverseSurface;
        ValueAnimator a = ValueAnimator.ofInt(focused ? 0 : 255, focused ? 255 : 0).setDuration(Motion.FAST_EFFECTS_MS);
        a.setInterpolator(Motion.FAST_EFFECTS);
        a.addUpdateListener(an -> ring.setStroke(dp(3), (color & 0x00FFFFFF) | ((int) an.getAnimatedValue() << 24)));
        a.start();
    }

    /** Standard button-group behaviour on the dock: the neighbours of the focused tile make a little room. */
    private void shiftNeighbours(View holder, boolean focused) {
        if (!(holder.getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) holder.getParent();
        int at = row.indexOfChild(holder);
        for (int i = 0; i < row.getChildCount(); i++) {
            float dx = !focused ? 0 : i == at - 1 ? -dp(8) : i == at + 1 ? dp(8) : 0;
            if (!focused && i != at - 1 && i != at + 1) continue;
            row.getChildAt(i).animate().translationX(dx)
                    .setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
    }

    private View activeLoader;
    private ValueAnimator loaderAnim;
    private final Runnable[] pendingLoader = new Runnable[1];

    /** Press feedback (a quick squash), then launch; if the app hasn't appeared after 300 ms, a shape loader morphs. */
    private void pressAndLaunch(View tile, ImageView loader, CookieDrawable shape, Intent intent) {
        tile.animate().scaleX(1.0f).scaleY(1.0f).setInterpolator(Motion.FAST_SPATIAL).setDuration(120)
                .withEndAction(() -> tile.animate().scaleX(1.10f).scaleY(1.10f)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start()).start();
        launch(intent);
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        pendingLoader[0] = () -> {
            hideLoader();
            activeLoader = loader;
            shape.setColor(palette.primary);
            loader.setAlpha(0f);
            loader.setVisibility(View.VISIBLE);
            loader.animate().alpha(1f).setDuration(Motion.FAST_EFFECTS_MS).start();
            final int[] seq = {4, 6, 7, 9, 12, 7, 6};
            final int[] step = {0};
            loaderAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
            loaderAnim.setRepeatCount(ValueAnimator.INFINITE);
            loaderAnim.setInterpolator(Motion.DEFAULT_SPATIAL);
            loaderAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationRepeat(android.animation.Animator a) {
                    step[0] = (step[0] + 1) % seq.length;
                    shape.morphTo(seq[step[0]], seq[step[0]] == 4 ? 0.18f : 0.10f);
                }
            });
            shape.morphTo(seq[0], 0.18f);
            loaderAnim.addUpdateListener(an -> {
                shape.setProgress((float) an.getAnimatedValue());
                loader.setRotation(loader.getRotation() + 2.5f);
            });
            loaderAnim.start();
        };
        handler.postDelayed(pendingLoader[0], 300);
    }

    private void hideLoader() {
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        if (loaderAnim != null) loaderAnim.cancel();
        if (activeLoader != null) activeLoader.setVisibility(View.GONE);
        activeLoader = null;
    }

    private void onTileFocused(String pkg, CharSequence label, Drawable art) {
        pendingPkg = pkg;
        pendingLabel = label;
        pendingArt = art;
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, 120);
    }

    /** Runs once focus has rested for a moment: the title, the background shape and the colors follow the app. */
    private void applyFocus(String pkg, CharSequence label, Drawable art) {
        if (pkg == null) return;
        if (!showingApps && !TextUtils.equals(focusTitle.getText(), label)) {
            focusTitle.setText(label);
            focusTitle.setAlpha(0f);
            focusTitle.setTranslationY(dp(12));
            focusTitle.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
            animateTitleWeight();
        }
        morphShape(pkg);
        retint(paletteFor(pkg, art));
    }

    private Palette paletteFor(String pkg, Drawable art) {
        Integer seed = seedCache.get(pkg);
        if (seed == null) {
            seed = Palette.seedFrom(art);
            seedCache.put(pkg, seed);
        }
        Palette p = paletteCache.get(seed);
        if (p == null) {
            p = Palette.of(seed);
            paletteCache.put(seed, p);
        }
        return p;
    }

    /** Work out every favorite's colors on a background thread, so the first focus on each app costs nothing. */
    private void warmPalettes(List<String> packages) {
        new Thread(() -> {
            for (String pkg : packages) {
                if (seedCache.containsKey(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    Drawable art = pm.getApplicationBanner(ai);
                    if (art == null) art = pm.getApplicationIcon(ai);
                    int seed = Palette.seedFrom(art);
                    seedCache.put(pkg, seed);
                    if (!paletteCache.containsKey(seed)) paletteCache.put(seed, Palette.of(seed));
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
        }, "palette-warmup").start();
    }

    /** Expressive type: the app name lands a little lighter and settles into its full weight. */
    private void animateTitleWeight() {
        ValueAnimator a = ValueAnimator.ofInt(0, 6).setDuration(Motion.DEFAULT_SPATIAL_MS);
        a.setInterpolator(Motion.DEFAULT_EFFECTS);
        a.addUpdateListener(an -> focusTitle.setTypeface(titleFont(560 + 30 * (int) an.getAnimatedValue())));
        a.start();
    }

    private Typeface titleFont(int weight) {
        Typeface t = titleFonts.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' 48, 'ROND' 0").build();
            } catch (RuntimeException e) {
                t = font(820);
            }
            titleFonts.put(weight, t);
        }
        return t;
    }

    /** Each app gets its own Expressive shape; moving between apps morphs one outline into the next. */
    private void morphShape(String pkg) {
        // Material 3 Expressive shapes (MaterialShapes): Cookie 9, Cookie 12, Clover 4-leaf, Clover 8-leaf, Sunny,
        // Soft burst, Cookie 6, Cookie 7, Cookie 4.
        int[] lobes = {9, 12, 4, 8, 14, 10, 6, 7, 4};
        float[] depths = {0.08f, 0.06f, 0.26f, 0.16f, 0.05f, 0.11f, 0.12f, 0.10f, 0.16f};
        float[] sharp = {1f, 1f, 0.55f, 0.65f, 1f, 2.4f, 1f, 1f, 1f};
        int k = Math.abs(pkg.hashCode()) % lobes.length;
        cookie.morphTo(lobes[k], depths[k], sharp[k]);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.DEFAULT_SPATIAL_MS);
        a.setInterpolator(Motion.DEFAULT_SPATIAL);
        a.addUpdateListener(an -> cookie.setProgress((float) an.getAnimatedValue()));
        a.start();
    }

    /** Cross-fade every tinted surface from the current palette to the next. */
    private void retint(Palette next) {
        if (next.seed == palette.seed) return;
        final Palette from = palette;
        palette = next;
        if (colorAnimator != null) colorAnimator.cancel();
        ArgbEvaluator argb = new ArgbEvaluator();
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(Motion.DEFAULT_EFFECTS_MS);
        colorAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        colorAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int top = (int) argb.evaluate(t, from.surfaceBright, next.surfaceBright);
            int bottom = (int) argb.evaluate(t, from.surface, next.surface);
            backdrop.setColors(new int[]{top, bottom});
            cookie.setColor((int) argb.evaluate(t, from.primaryContainer, next.primaryContainer));
            dockShape.setColor(withAlpha((int) argb.evaluate(t, from.container, next.container), 0xB3));
        });
        colorAnimator.start();
        nav.setPalette(next);
        styleNowPlaying();
    }

    private Intent launchIntent(String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    // ---- pages ----

    private void showHome() {
        showingApps = false;
        appsPage.setVisibility(View.GONE);
        homePage.setVisibility(View.VISIBLE);
        nav.select(0);
        if (nowCard != null && playing != null) showNowPlaying();
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    private void showApps() {
        showingApps = true;
        fillApps();
        homePage.setVisibility(View.GONE);
        appsPage.setVisibility(View.VISIBLE);
        appsPage.scrollTo(0, 0);
        nav.select(1);
        if (nowCard != null) nowCard.setVisibility(View.GONE);
        if (appsGrid.getChildCount() > 0) appsGrid.getChildAt(0).requestFocus();
    }

    // ---- Now Playing ----

    private View buildNowPlaying() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(14), dp(12));
        nowShape = new GradientDrawable();
        nowShape.setCornerRadius(dp(Focus.CARD));
        card.setBackground(nowShape);
        card.setVisibility(View.GONE);

        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artShape = new GradientDrawable();
        artShape.setCornerRadius(dp(Focus.MEDIA));
        artShape.setColor(0x33FFFFFF);
        nowArt.setBackground(artShape);
        nowArt.setClipToOutline(true);
        card.addView(nowArt, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, dp(10), 0);
        TextView label = text("NOW PLAYING", 12, palette.onSurfaceVariant, 760);
        label.setLetterSpacing(0.2f);
        words.addView(label);
        nowTitle = text("", 16, palette.onSurface, 760);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setPadding(0, dp(3), 0, 0);
        words.addView(nowTitle);
        nowArtist = text("", 14, palette.onSurfaceVariant, 500);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(nowArtist);
        nowProgress = new WavyProgress(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        pl.topMargin = dp(6);
        words.addView(nowProgress, pl);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nowButton = new ImageView(this);
        nowButton.setScaleType(ImageView.ScaleType.CENTER);
        nowButtonShape = new GradientDrawable();
        nowButtonShape.setShape(GradientDrawable.OVAL);
        nowButton.setBackground(nowButtonShape);
        nowButton.setFocusable(true);
        nowButton.setClickable(true);
        nowButton.setContentDescription("Play or pause");
        nowButton.setOnClickListener(v -> togglePlayback());
        nowButton.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            styleNowPlaying();
        });
        card.addView(nowButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        styleNowPlaying();
        return card;
    }

    private void styleNowPlaying() {
        if (nowShape == null) return;
        nowShape.setColor(withAlpha(palette.container, 0xD9));
        boolean focused = nowButton.hasFocus();
        nowButtonShape.setColor(focused ? palette.inverseSurface : palette.primary);
        nowButton.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onPrimary));
        nowTitle.setTextColor(palette.onSurface);
        nowArtist.setTextColor(palette.onSurfaceVariant);
        nowProgress.setColors(palette.primary, withAlpha(palette.onSurfaceVariant, 0x55));
    }

    private void startNowPlaying() {
        try {
            if (sessions == null) {
                sessions = getSystemService(MediaSessionManager.class);
                mediaListener = new ComponentName(this, MediaListener.class);
            }
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaListener);
            pickSession();
        } catch (SecurityException notAllowed) {
            // Notification access not granted: the card simply stays hidden.
            nowCard.setVisibility(View.GONE);
        }
    }

    private void stopNowPlaying() {
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
            }
        }
        if (playing != null) playing.unregisterCallback(mediaCallback);
        playing = null;
    }

    private void pickSession() {
        MediaController best = null;
        try {
            for (MediaController c : sessions.getActiveSessions(mediaListener)) {
                if (c.getMetadata() == null) continue;
                PlaybackState st = c.getPlaybackState();
                if (best == null || (st != null && st.getState() == PlaybackState.STATE_PLAYING)) best = c;
            }
        } catch (SecurityException e) {
            best = null;
        }
        if (playing != null && (best == null || !best.getSessionToken().equals(playing.getSessionToken()))) {
            playing.unregisterCallback(mediaCallback);
        }
        if (best != null && (playing == null || !best.getSessionToken().equals(playing.getSessionToken()))) {
            best.registerCallback(mediaCallback, handler);
        }
        playing = best;
        showNowPlaying();
    }

    /** The device's "on" dot: visible while the HDMI device reports connected (on), hidden in standby or unplugged. */
    private void showDeviceState(String inputId, int state) {
        View dot = deviceDots.get(inputId);
        if (dot == null) return;
        boolean on = state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
        if (on == (dot.getVisibility() == View.VISIBLE)) return;
        if (on) {
            dot.setScaleX(0f);
            dot.setScaleY(0f);
            dot.setVisibility(View.VISIBLE);
            dot.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
        } else {
            dot.animate().scaleX(0f).scaleY(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                    .withEndAction(() -> dot.setVisibility(View.GONE)).start();
        }
    }

    /** Show the equalizer badge on the dock tile of the app that's playing (if it's in the dock). */
    private void updateBadges() {
        PlaybackState st = playing != null ? playing.getPlaybackState() : null;
        String pkg = st != null && st.getState() == PlaybackState.STATE_PLAYING ? playing.getPackageName() : null;
        for (Map.Entry<String, EqualizerBadge> e : dockBadges.entrySet()) {
            boolean on = e.getKey().equals(pkg);
            EqualizerBadge b = e.getValue();
            if (on && b.getVisibility() != View.VISIBLE) {
                b.setAlpha(0f);
                b.setScaleX(0.6f);
                b.setScaleY(0.6f);
                b.setVisibility(View.VISIBLE);
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            } else if (!on && b.getVisibility() == View.VISIBLE) {
                b.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> b.setVisibility(View.GONE)).start();
            }
        }
    }

    private void showNowPlaying() {
        if (nowCard != null) updateBadges();
        MediaMetadata m = playing != null ? playing.getMetadata() : null;
        PlaybackState st = playing != null ? playing.getPlaybackState() : null;
        boolean active = m != null && st != null
                && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_PAUSED);
        if (!active || showingApps) {
            if (nowCard.getVisibility() == View.VISIBLE) {
                nowCard.animate().alpha(0f).setDuration(200).withEndAction(() -> nowCard.setVisibility(View.GONE)).start();
            }
            return;
        }
        CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        nowTitle.setText(title != null ? title : "");
        nowArtist.setText(artist != null ? artist : appLabel(playing.getPackageName()));
        nowArt.setImageBitmap(art);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        nowProgress.setVisibility(duration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(st.getPosition(), st.getLastPositionUpdateTime(), st.getPlaybackSpeed(), duration,
                st.getState() == PlaybackState.STATE_PLAYING);
        nowButton.setImageResource(st.getState() == PlaybackState.STATE_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play);
        styleNowPlaying();
        // Cancel a pending fade-out first: its end action would otherwise hide the card again.
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(0f);
            nowCard.setTranslationY(dp(10));
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
    }

    private void togglePlayback() {
        if (playing == null) return;
        PlaybackState st = playing.getPlaybackState();
        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) playing.getTransportControls().pause();
        else playing.getTransportControls().play();
    }

    private CharSequence appLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---- motion ----

    private void arrive(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0).setStartDelay(40L * index).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
    }

    // ---- helpers ----

    /** Google Sans Flex at a given weight, standard terminals (axes 'wght', 'opsz'; 'ROND' 0); falls back to sans-serif. */
    private Typeface font(int requested) {
        int weight = requested <= 400 ? 300 : requested <= 650 ? 560 : requested <= 790 ? 760 : 820;
        Typeface t = fonts.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' 24, 'ROND' 0")
                        .build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            fonts.put(weight, t);
        }
        return t;
    }

    private TextView text(CharSequence value, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
