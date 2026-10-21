package com.rampey.materialtv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** On-demand, text-only adapter for the vendored Android Use semantic compiler. No ADB transport. */
public final class AndroidUseService extends AccessibilityService {
    private static volatile AndroidUseService connected;
    private volatile long syntheticBackUntil;
    private final Object guard = new Object();

    static AndroidUseService connected() { return connected; }
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        android.util.Log.i("MaterialTvAndroidUse", "accessibility connected");
    }
    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }
    // Do not read, store or upload accessibility events. Reads happen only during a requested turn.
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { MaterialVoiceSession.cancelAndroidUse(); }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && MaterialVoiceSession.usingAndroid()
                && SystemClock.uptimeMillis() > syntheticBackUntil) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) MaterialVoiceSession.cancelAndroidUse();
            return true;
        }
        return false;
    }

    static final class Node {
        final AccessibilityNodeInfo info;
        final String label, hint, key;
        final long parent;
        final Rect bounds = new Rect();
        final boolean secret;
        Node(AccessibilityNodeInfo info, long parent, boolean inheritedSecret) {
            this.info = info;
            this.parent = parent;
            int variation = info.getInputType() & 0xfff;
            secret = inheritedSecret || info.isPassword() || variation == 0x81 || variation == 0x91
                    || variation == 0xe1 || variation == 0x12;
            label = secret ? "" : text(info.getText()).isEmpty() ? text(info.getContentDescription()) : text(info.getText());
            hint = secret ? "" : text(info.getHintText());
            info.getBoundsInScreen(bounds);
            key = text(info.getViewIdResourceName()) + "|" + text(info.getClassName()) + "|" + label
                    + "|" + hint + "|" + bounds.toShortString();
        }
        boolean interactive() { return info.isClickable() || info.isFocusable() || info.isScrollable() || info.isEditable(); }
    }

    static final class Screen implements AutoCloseable {
        final String pkg;
        final int window;
        final List<Node> nodes = new ArrayList<>();
        final Map<String, Node> targets = new HashMap<>();
        final List<String[]> names = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        final JSONObject json = new JSONObject();
        String token = "";
        boolean truncated;
        Screen(String pkg, int window) { this.pkg = pkg; this.window = window; }
        @Override public void close() { for (Node n : nodes) n.info.recycle(); }
    }

    /** Excludes the assistant, keyboard and system windows; only the chosen installed app is exposed. */
    Screen read(String wantedPackage) {
        synchronized (guard) {
            AccessibilityNodeInfo root = null;
            int best = Integer.MIN_VALUE;
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                try {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo candidate = window.getRoot();
                    if (candidate == null) continue;
                    String pkg = text(candidate.getPackageName());
                    if (getPackageName().equals(pkg) || !candidate.isVisibleToUser()
                            || (!wantedPackage.isEmpty() && !wantedPackage.equals(pkg))) {
                        candidate.recycle();
                        continue;
                    }
                    int score = window.getLayer();
                    if (score > best) {
                        if (root != null) root.recycle();
                        root = candidate;
                        best = score;
                    } else candidate.recycle();
                } finally { window.recycle(); }
            }
            if (root == null) return empty(wantedPackage, "App screen is unavailable");
            Screen screen = new Screen(text(root.getPackageName()), root.getWindowId());
            long deadline = SystemClock.uptimeMillis() + 1800;
            visit(screen, root, -1, 0, false, deadline);
            List<SemanticCompiler.Item> items = new ArrayList<>();
            for (int i = 0; i < screen.nodes.size(); i++) {
                Node n = screen.nodes.get(i);
                AccessibilityNodeInfo a = n.info;
                String role = text(a.getClassName()), lower = role.toLowerCase(Locale.ROOT);
                items.add(new SemanticCompiler.Item(i, n.parent, n.label, n.hint, role,
                        a.isClickable() || a.isFocusable(), a.isEnabled(), a.isCheckable(), a.isChecked(), a.isEditable(),
                        n.secret, !n.secret && !text(a.getText()).isEmpty(), a.isScrollable(), a.isSelected(), a.isHeading(),
                        lower.contains("button"), lower.contains("link"), lower.contains("radio"), lower.contains("checkbox"),
                        lower.contains("seekbar"), a.isVisibleToUser(), false,
                        n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom));
            }
            JSONArray rows = new JSONArray();
            StringBuilder signature = new StringBuilder(screen.pkg).append('|').append(screen.window);
            // TV focus can move without changing any label; include even unlabelled focus nodes.
            for (Node node : screen.nodes) if (node.info.isFocused()) signature.append("|focus:").append(node.key);
            int chars = 0;
            for (SemanticCompiler.Row row : SemanticCompiler.compile(items)) {
                String id = "n" + row.source;
                Node n = row.source >= 0 && row.source < screen.nodes.size() ? screen.nodes.get((int) row.source) : null;
                String label = text(row.label), value = text(row.value);
                if (label.isEmpty() && value.isEmpty()) continue;
                if (rows.length() >= 100 || chars + label.length() + value.length() > 14000) { screen.truncated = true; break; }
                JSONObject out = new JSONObject();
                put(out, "text", label);
                if (!value.isEmpty()) put(out, "value", value);
                put(out, "kind", row.kind);
                if (!row.state.isEmpty()) put(out, "state", row.state);
                if (n != null && n.interactive() && !n.secret) {
                    screen.targets.put(id, n);
                    screen.names.add(new String[]{label, n.hint});
                    screen.ids.add(id);
                    put(out, "id", id);
                    put(out, "enabled", row.enabled);
                    put(out, "focused", n.info.isFocused());
                    signature.append('|').append(n.key).append(':').append(label).append(':').append(value)
                            .append(':').append(row.state).append(':').append(row.enabled);
                }
                rows.put(out);
                chars += label.length() + value.length();
            }
            screen.token = digest(signature.toString());
            put(screen.json, "package", screen.pkg);
            put(screen.json, "screen_token", screen.token);
            put(screen.json, "rows", rows);
            put(screen.json, "truncated", screen.truncated);
            put(screen.json, "remote_keys", TvRemote.enabled(this));
            put(screen.json, "touch_gestures", getPackageManager().hasSystemFeature("android.hardware.touchscreen"));
            return screen;
        }
    }

    private void visit(Screen s, AccessibilityNodeInfo info, long parent, int depth, boolean secret, long deadline) {
        if (s.nodes.size() >= 512 || depth > 32 || SystemClock.uptimeMillis() > deadline) {
            info.recycle();
            s.truncated = true;
            return;
        }
        Node n = new Node(info, parent, secret);
        int id = s.nodes.size();
        s.nodes.add(n);
        int count = Math.min(128, info.getChildCount());
        for (int i = 0; i < count; i++) {
            if (s.nodes.size() >= 512 || SystemClock.uptimeMillis() > deadline) { s.truncated = true; break; }
            AccessibilityNodeInfo child = info.getChild(i);
            if (child != null) visit(s, child, id, depth + 1, n.secret, deadline);
        }
    }

    /** Refresh immediately before acting. IDs only refer to the screen the model actually saw. */
    String act(Screen observed, JSONObject action, BooleanSupplier current) {
        synchronized (guard) {
            if (!current.getAsBoolean()) return "cancelled";
            try (Screen fresh = read(observed.pkg)) {
                if (!observed.token.equals(fresh.token) || fresh.window != observed.window || fresh.window < 0)
                    return "Screen changed; read it again";
                String type = action.optString("type");
                if (type.equals("back")) {
                    if (!current.getAsBoolean()) return "cancelled";
                    syntheticBackUntil = SystemClock.uptimeMillis() + 500;
                    return performGlobalAction(GLOBAL_ACTION_BACK) ? null : "Back was unavailable";
                }
                if (type.equals("key")) {
                    if (!targetFocused(observed.pkg)) return "App is no longer focused; read again";
                    return TvRemote.press(this, action.optString("key"),
                            () -> current.getAsBoolean() && targetFocused(observed.pkg));
                }
                String id = action.optString("target");
                if (id.isEmpty() && !action.optString("label").isEmpty()) {
                    List<Integer> hits = Targets.match(observed.names, action.optString("label"));
                    if (hits.size() != 1) return "Target is missing or ambiguous; read the screen again";
                    id = observed.ids.get(hits.get(0));
                }
                Node prior = observed.targets.get(id);
                if (prior == null) return "Choose an id from the screen";
                Node target = null;
                for (Node n : fresh.targets.values()) {
                    if (prior.key.equals(n.key)) {
                        if (target != null) return "Target is ambiguous";
                        target = n;
                    }
                }
                if (target == null || !target.info.isEnabled() || target.secret || !target.info.isVisibleToUser())
                    return "Target is unavailable";
                if (!current.getAsBoolean()) return "cancelled";
                switch (type) {
                    case "click":
                        if (click(target.info)) return null;
                        // Some TV views expose focus but no click. Focus that exact target, then use OK.
                        if (TvRemote.enabled(this) && targetFocused(observed.pkg)
                                && (target.info.isFocused() || target.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)))
                            return TvRemote.press(this, "ok", () -> current.getAsBoolean() && targetFocused(observed.pkg));
                        return "App did not accept this click; use TV remote keys if available";
                    case "focus":
                        return target.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ? null : "App did not accept focus";
                    case "scroll": {
                        String direction = action.optString("direction");
                        int scroll = direction.equals("backward") ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                : direction.equals("forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : 0;
                        if (scroll == 0 || !target.info.isScrollable()) return "Choose a scrollable target and forward or backward";
                        return target.info.performAction(scroll) ? null : "App did not accept this scroll";
                    }
                    case "type": {
                        String text = action.optString("text");
                        if (!target.info.isEditable() || text.length() > 300) return "Choose a non-password text field";
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        return target.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ? null : "App did not accept text";
                    }
                    default: return "Unsupported Android use action";
                }
            }
        }
    }

    private boolean targetFocused(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try { return pkg.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()); }
        finally { root.recycle(); }
    }

    private boolean click(AccessibilityNodeInfo node) {
        // TV apps sometimes put the label under the clickable row rather than on it.
        AccessibilityNodeInfo cursor = AccessibilityNodeInfo.obtain(node);
        try {
            for (int i = 0; cursor != null && i < 5; i++) {
                if (cursor.isClickable() && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor.recycle();
                cursor = parent;
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally { if (cursor != null) cursor.recycle(); }
    }

    String actImage(Screen observed, AndroidUseImage image, JSONObject action, BooleanSupplier current)
            throws InterruptedException {
        if (image == null || !current.getAsBoolean()) return "No current screen image";
        if (SystemClock.elapsedRealtime() - image.capturedAt > 15000) return "Image is too old; read again";
        try (Screen fresh = read(observed.pkg)) {
            if (!observed.pkg.equals(fresh.pkg) || observed.window != fresh.window || fresh.window < 0)
                return "App window changed; read again";
            if (!observed.targets.isEmpty() && !observed.token.equals(fresh.token)) return "Screen changed; read again";
        }
        String type = action.optString("type");
        double nx = action.optDouble("x", -1), ny = action.optDouble("y", -1);
        if (!Double.isFinite(nx) || !Double.isFinite(ny) || nx < 0 || nx > 1000 || ny < 0 || ny > 1000)
            return "Use normalized x and y coordinates from 0 to 1000";
        float x = (float) (nx * (image.width - 1) / 1000), y = (float) (ny * (image.height - 1) / 1000);
        Path path = new Path();
        path.moveTo(x, y);
        long duration = 60;
        if (type.equals("swipe")) {
            double ex = action.optDouble("end_x", -1), ey = action.optDouble("end_y", -1);
            if (!Double.isFinite(ex) || !Double.isFinite(ey) || ex < 0 || ex > 1000 || ey < 0 || ey > 1000)
                return "Use valid end_x and end_y coordinates";
            path.lineTo((float) (ex * (image.width - 1) / 1000), (float) (ey * (image.height - 1) / 1000));
            duration = 400;
        } else if (!type.equals("tap")) return "Unsupported image action";
        if (!current.getAsBoolean()) return "cancelled";
        CountDownLatch done = new CountDownLatch(1);
        boolean[] accepted = {false};
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean sent = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) { accepted[0] = true; done.countDown(); }
            @Override public void onCancelled(GestureDescription gesture) { done.countDown(); }
        }, null);
        if (!sent || !done.await(2, TimeUnit.SECONDS)) return "This TV did not accept the touch gesture";
        return accepted[0] ? null : "Touch gesture was cancelled";
    }

    private static Screen empty(String pkg, String reason) {
        Screen s = new Screen(pkg, -1);
        put(s.json, "package", pkg);
        put(s.json, "error", reason);
        put(s.json, "rows", new JSONArray());
        return s;
    }
    private static String text(CharSequence value) {
        if (value == null) return "";
        String s = value.toString().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return s.length() > 320 ? s.substring(0, 320) + "…" : s;
    }
    private static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

}
