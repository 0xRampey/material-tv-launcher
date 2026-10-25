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
            android.graphics.Bitmap fixture = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888);
            try {
                fixture.eraseColor(android.graphics.Color.BLACK);
                android.graphics.Rect assistant = new android.graphics.Rect(200, 200, 440, 360);
                String initialPixels = AndroidUseImage.progressToken(fixture, 640, 360, assistant);
                android.graphics.Canvas canvas = new android.graphics.Canvas(fixture);
                android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(android.graphics.Color.WHITE);
                canvas.drawRect(assistant, paint);
                if (!initialPixels.equals(AndroidUseImage.progressToken(fixture, 640, 360, assistant)))
                    throw new AssertionError("Assistant animation must not count as app progress");
                canvas.drawRect(0, 0, 100, 100, paint);
                if (initialPixels.equals(AndroidUseImage.progressToken(fixture, 640, 360, assistant)))
                    throw new AssertionError("App changes outside assistant must count as progress");
                if (AndroidUseImage.progressToken(fixture, 640, 360, null).equals(initialPixels))
                    throw new AssertionError("Without an overlay the full app image must be compared");
                result.putString("image_progress_checks", "Assistant pixels excluded; app pixels detected; full-screen mode checked");
            } finally { fixture.recycle(); }
            Bundle starting = new Bundle(); starting.putInt("test_pid", android.os.Process.myPid());
            sendStatus(1, starting);
            long until = SystemClock.elapsedRealtime() + 30000;
            while (AndroidUseService.connected() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
            AndroidUseService service = AndroidUseService.connected();
            if (service == null) throw new IllegalStateException("Android use service not bound");
            String pkg = args.getString("package", "");
            String request = args.getString("request", "");
            if (args.getString("mode", "").equals("overlay")) {
                runOnMainSync(() -> {
                    Intent launch = args.getString("from_home", "").equals("true")
                            ? new Intent(getTargetContext(), HomeActivity.class)
                            : getTargetContext().getPackageManager().getLeanbackLaunchIntentForPackage(args.getString("start_package", pkg));
                    getTargetContext().startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                });
                Thread.sleep(2000);
                if (args.getString("authorize", "").equals("true")) {
                    Bundle status = new Bundle(); status.putString("progress", "Approve one-time remote consent; Always allow unchecked");
                    sendStatus(1, status);
                    String error = TvRemote.authorize(getTargetContext(), () -> true);
                    if (error != null) throw new IllegalStateException(error);
                }
                java.lang.reflect.Field visible = MaterialVoiceSession.class.getDeclaredField("visibleSession");
                visible.setAccessible(true);
                java.lang.reflect.Method settings = MaterialVoiceSession.class.getDeclaredMethod("showSettings");
                settings.setAccessible(true);
                java.lang.reflect.Field generation = MaterialVoiceSession.class.getDeclaredField("generation");
                generation.setAccessible(true);
                java.lang.reflect.Field activeTurn = MaterialVoiceSession.class.getDeclaredField("androidUse");
                activeTurn.setAccessible(true);
                java.lang.reflect.Field statusText = MaterialVoiceSession.class.getDeclaredField("status");
                statusText.setAccessible(true);
                java.lang.reflect.Method startTurn = MaterialVoiceSession.class.getDeclaredMethod("startAndroidUse",
                        int.class, String.class, String.class, JSONObject.class);
                startTurn.setAccessible(true);
                boolean[] shown = {false};
                long readyUntil = SystemClock.elapsedRealtime() + 15000;
                while (!shown[0] && SystemClock.elapsedRealtime() < readyUntil) {
                    runOnMainSync(() -> shown[0] = AssistService.showAssistant());
                    if (!shown[0]) Thread.sleep(100);
                }
                if (!shown[0]) throw new IllegalStateException("Assistant not ready");
                MaterialVoiceSession[] session = {null};
                long showUntil = SystemClock.elapsedRealtime() + 5000;
                while (session[0] == null && SystemClock.elapsedRealtime() < showUntil) {
                    runOnMainSync(() -> {
                        try {
                            session[0] = (MaterialVoiceSession) visible.get(null);
                            // Stop capture before any audio request; the explicit test uses text below.
                            if (session[0] != null) settings.invoke(session[0]);
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    if (session[0] == null) Thread.sleep(25);
                }
                if (session[0] == null) throw new IllegalStateException("Assistant window did not appear");
                VoiceActions plannerActions = new VoiceActions(getTargetContext(), intent -> { });
                JSONObject[] taskContext = {null};
                runOnMainSync(() -> taskContext[0] = plannerActions.context(new JSONArray()));
                taskContext[0].put("android_use_available", true);
                VoiceAgent.Result plan = new VoiceAgent(new File(getTargetContext().getExternalFilesDir(null), "openrouter_key"))
                        .ask(request, taskContext[0]);
                result.putString("plan_actions", plan.actions.toString());
                if (plan.error != null || plan.actions.length() != 1
                        || !"android_use".equals(plan.actions.getJSONObject(0).optString("type"))
                        || !pkg.equals(plan.actions.getJSONObject(0).optString("package")))
                    throw new IllegalStateException("Request did not route to the expected Android use app");
                runOnMainSync(() -> {
                    try {
                        java.lang.reflect.Field panel = MaterialVoiceSession.class.getDeclaredField("settings");
                        panel.setAccessible(true);
                        ((android.view.View) panel.get(session[0])).setVisibility(android.view.View.GONE);
                        startTurn.invoke(session[0], generation.getInt(session[0]), request, pkg, taskContext[0]);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                boolean[] running = {true};
                long loopUntil = SystemClock.elapsedRealtime() + 85_000;
                while (running[0] && SystemClock.elapsedRealtime() < loopUntil) {
                    Thread.sleep(100);
                    runOnMainSync(() -> {
                        try {
                            running[0] = activeTurn.get(session[0]) != null;
                            result.putString("reply", ((android.widget.TextView) statusText.get(session[0])).getText().toString());
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                }
                if (running[0]) throw new IllegalStateException("Overlay turn did not finish");
                try (AndroidUseService.Screen screen = service.read(pkg)) {
                    result.putString("after", screen.json.toString());
                    result.putInt("after_window", screen.window);
                }
                result.putString("overlay_check", "Actual assistant window and production AndroidUseTurn used; synthetic text, not physical microphone");
                runOnMainSync(MaterialVoiceSession::dismissActive);
                finish(-1, result);
                return;
            }
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
