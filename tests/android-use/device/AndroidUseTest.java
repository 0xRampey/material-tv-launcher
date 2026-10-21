package com.rampey.materialtv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional, separately signed test APK. No test-command entry point is shipped in the launcher. */
public final class AndroidUseTest extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args = args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long until = SystemClock.elapsedRealtime() + 15000;
            while (AndroidUseService.connected() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
            AndroidUseService service = AndroidUseService.connected();
            if (service == null) throw new IllegalStateException("Android use service not bound");
            String pkg = args.getString("package", "");
            String request = args.getString("request", "");
            if (args.getString("mode", "").equals("remote")) {
                if (TvRemote.keyCode("up; reboot") != -1 || TvRemote.keyCode("home") != -1)
                    throw new AssertionError("Remote key allowlist must reject arbitrary commands");
                if (!"cancelled".equals(TvRemote.authorize(getTargetContext(), () -> false)))
                    throw new AssertionError("Cancelled authorization must not connect");
                runOnMainSync(() -> getTargetContext().startActivity(getTargetContext().getPackageManager()
                        .getLeanbackLaunchIntentForPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                Thread.sleep(2000);
                Bundle status = new Bundle(); status.putString("progress", "Approve one-time debugging consent (Always allow unchecked)");
                sendStatus(1, status);
                String authorization = TvRemote.authorize(getTargetContext(), () -> true);
                if (authorization != null) throw new IllegalStateException(authorization);
                Thread.sleep(1200);
                for (String key : new String[]{"left", "right"}) {
                    String rejection = "not attempted";
                    for (int attempt = 0; attempt < 4; attempt++) {
                        try (AndroidUseService.Screen screen = service.read(pkg)) {
                            JSONObject action = new JSONObject().put("type", "key").put("key", key);
                            rejection = service.act(screen, action, () -> true);
                            result.putString("observed_screen", screen.json.toString());
                            result.putInt("observed_window", screen.window);
                            try (AndroidUseService.Screen fresh = service.read(pkg)) {
                                result.putString("fresh_screen", fresh.json.toString());
                                result.putInt("fresh_window", fresh.window);
                            }
                            JSONArray windows = new JSONArray();
                            for (android.view.accessibility.AccessibilityWindowInfo window : service.getWindows()) {
                                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                                if (root != null) {
                                    windows.put(new JSONObject().put("package", String.valueOf(root.getPackageName()))
                                            .put("visible", root.isVisibleToUser()).put("type", window.getType())
                                            .put("active", window.isActive()).put("focused", window.isFocused()));
                                    root.recycle();
                                }
                                window.recycle();
                            }
                            result.putString("windows", windows.toString());
                        }
                        if (rejection == null || !rejection.contains("changed")) break;
                        Thread.sleep(500);
                    }
                    if (rejection != null) throw new IllegalStateException("Native " + key + " failed: " + rejection);
                    result.putString("remote_" + key, "TV confirmed key execution");
                    Thread.sleep(650);
                }
                result.putString("remote_checks", "cancelled consent, allowlist, native LEFT and RIGHT passed");
            }
            if (args.getString("mode", "").equals("capabilities")) {
                JSONArray system = new JSONArray();
                for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a : service.getSystemActions())
                    system.put(new JSONObject().put("id", a.getId()).put("label", String.valueOf(a.getLabel())));
                result.putString("system_actions", system.toString());
                try (AndroidUseService.Screen screen = service.read(pkg)) {
                    result.putString("screen", screen.json.toString());
                    AndroidUseImage image = AndroidUseImage.capture(service, () -> true);
                    result.putBoolean("screenshot", image != null);
                    // Tap the neutral title area, not a setting or user content.
                    JSONObject tap = new JSONObject().put("type", "tap").put("x", 700).put("y", 100);
                    long at = SystemClock.elapsedRealtime();
                    result.putString("touch_result", String.valueOf(service.actImage(screen, image, tap, () -> true)));
                    result.putLong("touch_ms", SystemClock.elapsedRealtime() - at);
                }
                finish(-1, result);
                return;
            }
            try (AndroidUseService.Screen screen = service.read(pkg)) {
                result.putString("before", screen.json.toString());
                result.putInt("test_nodes", screen.nodes.size());
                int visible = 0, labeled = 0;
                for (AndroidUseService.Node node : screen.nodes) {
                    if (node.info.isVisibleToUser()) visible++;
                    if (!node.label.isEmpty()) labeled++;
                }
                result.putInt("test_visible", visible);
                result.putInt("test_labeled", labeled);
                JSONObject invalid = new JSONObject().put("type", "click").put("target", "not-a-target");
                if (!"cancelled".equals(service.act(screen, invalid, () -> false)))
                    throw new AssertionError("cancelled turn must not act");
                String token = screen.token;
                screen.token = "old-screen";
                String stale = service.act(screen, invalid, () -> true);
                screen.token = token;
                if (stale == null || !stale.contains("changed")) throw new AssertionError("stale screen must not act");
                result.putString("guard_checks", "cancelled and stale targets rejected");
                result.putBoolean("screen_images_enabled", getTargetContext().getSharedPreferences("voice", 0)
                        .getBoolean("screen_images", false));
            }
            AtomicInteger completions = new AtomicInteger();
            AndroidUseTurn timed = new AndroidUseTurn(getTargetContext(), null, null, "timeout guard check", pkg,
                    new JSONObject(), progress -> { }, reply -> completions.incrementAndGet());
            java.lang.reflect.Field timer = AndroidUseTurn.class.getDeclaredField("timeout");
            timer.setAccessible(true);
            Runnable timeout = (Runnable) timer.get(timed);
            runOnMainSync(() -> { timeout.run(); timeout.run(); });
            timed.cancel();
            if (completions.get() != 1) throw new AssertionError("timeout must complete exactly once");
            result.putString("timeout_check", "timeout completes once and cancels the turn");
            if (!request.isEmpty()) {
                VoiceActions actions = new VoiceActions(getTargetContext(), intent -> getTargetContext()
                        .startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                JSONObject[] context = {null};
                runOnMainSync(() -> context[0] = actions.context(new JSONArray()));
                VoiceAgent agent = new VoiceAgent(new File(getTargetContext().getExternalFilesDir(null), "openrouter_key"));
                if (args.getString("mode", "").equals("plan")) {
                    context[0].put("android_use_available", true);
                    VoiceAgent.Result plan = agent.ask(request, context[0]);
                    result.putString("plan_actions", plan.actions.toString());
                    result.putString("plan_say", plan.say);
                    if (plan.error != null) throw new IllegalStateException(plan.error);
                    finish(-1, result);
                    return;
                }
                CountDownLatch done = new CountDownLatch(1);
                AndroidUseTurn turn = new AndroidUseTurn(getTargetContext(), agent, actions, request, pkg, context[0],
                        progress -> { Bundle b = new Bundle(); b.putString("progress", progress); sendStatus(1, b); },
                        reply -> { result.putString("reply", reply); done.countDown(); });
                turn.start();
                if (!done.await(95, TimeUnit.SECONDS)) { turn.cancel(); throw new IllegalStateException("turn timed out"); }
                try (AndroidUseService.Screen screen = service.read(pkg)) { result.putString("after", screen.json.toString()); }
            }
            finish(-1, result);
        } catch (Exception failure) {
            result.putString("error", failure.toString());
            finish(0, result);
        }
    }
}
