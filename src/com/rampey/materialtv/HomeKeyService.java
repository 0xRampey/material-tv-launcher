package com.rampey.materialtv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV ignores a different default Home app, so this sends the remote's Home button here, and also
 * steps in whenever the stock home screen (Google TV's, or classic Android TV's) comes to the front, after boot or
 * when an app exits to Home.
 * It reads no screen content and sends nothing anywhere.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx";
    private static final String ANDROID_TV_HOME = "com.google.android.tvlauncher";
    private long lastOpen;
    // If this home screen fails to open, hand Home back to Google TV for a minute, then try again (a single failure,
    // e.g. right after an update, must not disable the Home button for good).
    private long cannotOpenUntil;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (blocked() || event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (blocked() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        String c = cls.toString();
        if ((GOOGLE_TV_HOME.contentEquals(pkg) && c.endsWith(".HomeActivity"))
                || (ANDROID_TV_HOME.contentEquals(pkg) && c.endsWith(".MainActivity"))) {
            openHome();
        }
    }

    private boolean blocked() {
        return SystemClock.elapsedRealtime() < cannotOpenUntil;
    }

    private void openHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpen < 700) return;
        lastOpen = now;
        tryOpen(0);
    }

    private final android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());

    private void tryOpen(int attempt) {
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(home);
        } catch (RuntimeException e) {
            if (attempt < 2) {
                retry.postDelayed(() -> tryOpen(attempt + 1), attempt == 0 ? 600 : 1500);
                return;
            }
            // Still failing: hand Home back to Google TV for a minute, then try again.
            cannotOpenUntil = SystemClock.elapsedRealtime() + 60_000;
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    @Override protected void onServiceConnected() {
        // The system binds this service a moment after boot, when Google TV's home screen is already up and has
        // sent its last window event. In the first minutes after boot, bring this home screen up directly.
        if (SystemClock.elapsedRealtime() < 5 * 60_000) openHome();
    }

    @Override public void onInterrupt() {
    }
}
