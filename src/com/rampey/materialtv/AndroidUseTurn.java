package com.rampey.materialtv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A bounded observe -> Gemini -> one action -> observe loop, running entirely from the launcher. */
final class AndroidUseTurn {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context app;
    private final VoiceAgent agent;
    private final VoiceActions actions;
    private final String request, pkg;
    private final JSONObject context;
    private final Consumer<String> progress;
    private final Consumer<String> finished;
    private final long deadline = SystemClock.elapsedRealtime() + 75_000;
    private final boolean imagesAllowed;
    private final Rect assistantBounds;
    private double cost;
    private int tokensIn, tokensOut, steps;
    private final Runnable timeout = this::timeOut;
    private void timeOut() {
        if (!cancelled.get() && ended.compareAndSet(false, true)) {
            cancelled.set(true);
            finished.accept("Couldn't finish that in time. You can take over");
        }
    }

    AndroidUseTurn(Context app, VoiceAgent agent, VoiceActions actions, String request, String pkg, JSONObject context,
                   Consumer<String> progress, Consumer<String> finished) {
        this(app, agent, actions, request, pkg, context, progress, finished, null);
    }

    AndroidUseTurn(Context app, VoiceAgent agent, VoiceActions actions, String request, String pkg, JSONObject context,
                   Consumer<String> progress, Consumer<String> finished, Rect assistantBounds) {
        this.app = app;
        this.agent = agent;
        this.actions = actions;
        this.request = request;
        this.pkg = pkg;
        this.context = context;
        this.progress = progress;
        this.finished = finished;
        this.assistantBounds = assistantBounds == null ? null : new Rect(assistantBounds);
        imagesAllowed = app.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("screen_images", false);
    }

    void start() { main.postDelayed(timeout, 75_000); new Thread(this::run, "tv-android-use").start(); }
    void cancel() { cancelled.set(true); ended.set(true); main.removeCallbacks(timeout); }
    private boolean current() { return !cancelled.get() && !ended.get() && SystemClock.elapsedRealtime() < deadline; }

    private void run() {
        String reply = "Couldn't finish that in time. You can take over";
        try {
            AndroidUseService service = AndroidUseService.connected();
            if (service == null) { complete("Enable Android use in Accessibility first"); return; }
            if (!installedTarget()) { complete("Couldn't find that TV app"); return; }
            JSONArray history = new JSONArray();
            try (AndroidUseService.Screen initial = service.read(pkg)) {
                if (initial.window < 0) {
                    String failure = onMain(() -> actions.run(new JSONArray().put(action("open", "package", pkg))));
                    if (failure != null) { complete(failure); return; }
                }
            }
            int rejected = 0;
            int unchanged = 0;
            int malformed = 0;
            String priorView = null;
            boolean priorAccepted = false;
            for (; steps < 12 && current(); steps++) {
                try (AndroidUseService.Screen screen = awaitAppWindow(service)) {
                    if (!current()) return;
                    if (screen.window < 0) { reply = "Couldn't read the app window. You can take over"; break; }
                    AndroidUseImage image = imagesAllowed ? AndroidUseImage.capture(service, this::current, assistantBounds) : null;
                    if (screen.targets.isEmpty() && image == null) {
                        reply = imagesAllowed ? "Couldn't read this screen. You can take over"
                                : "This screen needs images. Enable them in voice settings";
                        break;
                    }
                    String view = image == null ? screen.token : screen.token + ":" + image.progressToken;
                    if (priorView != null && priorAccepted) {
                        boolean changed = !priorView.equals(view);
                        screen.json.put("previous_action_changed_screen", changed);
                        android.util.Log.i("MaterialTvAndroidUse", "step=" + steps + " screen_changed=" + changed);
                        unchanged = changed ? 0 : unchanged + 1;
                        if (unchanged >= 3) { reply = "Those controls aren't changing this screen. You can take over"; break; }
                    }
                    priorView = view;
                    context.put("now_playing", playback());
                    VoiceAgent.Result plan = agent.androidStep(request, context, screen.json, history,
                            image == null ? null : image.base64);
                    cost += plan.cost;
                    tokensIn += plan.tokensIn;
                    tokensOut += plan.tokensOut;
                    if (!current()) return;
                    boolean unreadable = plan.error != null && plan.error.startsWith("JSON");
                    if (plan.error != null && !unreadable) { reply = "Couldn't reach Gemini. You can take over"; break; }
                    if (unreadable || plan.actions.length() != 1 || plan.actions.optJSONObject(0) == null) {
                        android.util.Log.w("MaterialTvAndroidUse", "invalid_plan step=" + steps + " action_count=" + plan.actions.length());
                        if (++malformed > 1) { reply = "Couldn't read the next step. Please try again"; break; }
                        history.put(new JSONObject().put("step", steps).put("result",
                                "Invalid response: return exactly ONE action object, including done or stop. No action was executed."));
                        priorAccepted = false;
                        continue;
                    }
                    malformed = 0;
                    JSONObject action = plan.actions.optJSONObject(0);
                    if (action == null) break;
                    String type = action.optString("type");
                    int remoteCode = type.equals("key") ? TvRemote.keyCode(action.optString("key")) : -1;
                    android.util.Log.i("MaterialTvAndroidUse", "step=" + steps + " type=" + type
                            + (remoteCode < 0 ? "" : " key_code=" + remoteCode) + " model_ms=" + plan.millis);
                    if (type.equals("done") || type.equals("stop")) {
                        reply = plan.say.isEmpty() ? "Done" : plan.say;
                        break;
                    }
                    post(() -> progress.accept(plan.say.isEmpty() ? "Working in the app…" : plan.say));
                    String result;
                    if (type.equals("wait")) result = null;
                    else if (type.equals("tap") || type.equals("swipe"))
                        result = service.actImage(screen, image, action, this::current);
                    else if (screen.targets.isEmpty() && !type.equals("back") && !type.equals("key")) result = "No readable controls; use remote keys when available";
                    else result = service.act(screen, action, this::current);
                    android.util.Log.i("MaterialTvAndroidUse", "step=" + steps + " type=" + type
                            + " accepted=" + (result == null) + (result == null ? "" : " reason=" + result));
                    history.put(new JSONObject().put("step", steps).put("action", action).put("say", plan.say)
                            .put("result", result == null ? "Action accepted; verify the next screen" : result));
                    rejected = result == null ? 0 : rejected + 1;
                    priorAccepted = result == null && !type.equals("wait");
                    if (rejected >= 3) { reply = "The TV isn't accepting these controls. You can take over"; break; }
                    settle(type.equals("wait") ? 1000 : result == null ? 650 : 150);
                }
            }
        } catch (Exception failure) {
            android.util.Log.w("MaterialTvAndroidUse", "turn failed=" + failure.getClass().getSimpleName());
            reply = "Couldn't finish that. You can take over";
        } finally {
            android.util.Log.i("MaterialTvAndroidUse", "finished steps=" + steps + " cancelled=" + cancelled.get()
                    + " tokens_in=" + tokensIn + " tokens_out=" + tokensOut + " cost=" + cost);
        }
        complete(reply);
    }

    /** App launches and window transitions do not consume model/action steps. */
    private AndroidUseService.Screen awaitAppWindow(AndroidUseService service) throws InterruptedException {
        long started = SystemClock.elapsedRealtime();
        long until = Math.min(deadline, started + 6_000);
        AndroidUseService.Screen screen = service.read(pkg);
        try {
            while (screen.window < 0 && current() && SystemClock.elapsedRealtime() < until) {
                settle(250);
                if (!current()) break;
                screen.close();
                screen = null;
                screen = service.read(pkg);
            }
            if (screen.window < 0) android.util.Log.w("MaterialTvAndroidUse", "window_unavailable step=" + steps
                    + " waited_ms=" + (SystemClock.elapsedRealtime() - started)
                    + " reason=" + screen.json.optString("window_diagnostic", "unknown")
                    + " application_windows=" + screen.json.optInt("application_windows")
                    + " readable_roots=" + screen.json.optInt("readable_roots"));
            return screen;
        } catch (Exception failure) {
            if (screen != null) screen.close();
            throw failure;
        }
    }

    private boolean installedTarget() {
        JSONArray apps = context.optJSONArray("apps");
        if (request.isEmpty() || request.length() > 1500 || apps == null || app.getPackageName().equals(pkg)) return false;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject candidate = apps.optJSONObject(i);
            if (candidate != null && pkg.equals(candidate.optString("package"))) return true;
        }
        return false;
    }

    private JSONObject playback() {
        JSONObject out = new JSONObject();
        try {
            MediaSessionManager manager = app.getSystemService(MediaSessionManager.class);
            if (manager == null) return out;
            for (MediaController controller : manager.getActiveSessions(new ComponentName(app, MediaListener.class))) {
                if (!pkg.equals(controller.getPackageName())) continue;
                PlaybackState state = controller.getPlaybackState();
                out.put("app", pkg).put("playing", state != null && state.getState() == PlaybackState.STATE_PLAYING);
                MediaMetadata metadata = controller.getMetadata();
                if (metadata != null) out.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                        .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                break;
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void settle(int millis) throws InterruptedException {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + millis);
        while (current() && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
    }
    private String onMain(Supplier<String> action) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        String[] result = {"cancelled"};
        AtomicBoolean pending = new AtomicBoolean(true);
        main.post(() -> {
            try { if (current() && pending.compareAndSet(true, false)) result[0] = action.get(); }
            finally { done.countDown(); }
        });
        if (!done.await(3, TimeUnit.SECONDS)) { pending.set(false); return "The TV is busy"; }
        return result[0];
    }
    private void post(Runnable callback) { main.post(() -> { if (!cancelled.get()) callback.run(); }); }
    private void complete(String message) {
        main.post(() -> {
            if (!cancelled.get() && ended.compareAndSet(false, true)) {
                main.removeCallbacks(timeout);
                finished.accept(message);
            }
        });
    }
    private static JSONObject action(String type, String key, String value) {
        JSONObject out = new JSONObject();
        try { out.put("type", type).put(key, value); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return out;
    }
}
