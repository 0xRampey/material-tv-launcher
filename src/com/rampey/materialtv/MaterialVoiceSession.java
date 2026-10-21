package com.rampey.materialtv;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rampey.voice.Heard;
import com.rampey.voice.Utterance;
import com.rampey.voice.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** A small assistant window; listening never starts an Activity or replaces the current app. */
final class MaterialVoiceSession extends VoiceInteractionSession {
    private static MaterialVoiceSession visibleSession;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Palette colors = Palette.of(Palette.DEFAULT_SEED);
    private final java.util.Map<String, Palette> appColors = new java.util.HashMap<>();
    private final java.util.Map<Integer, Typeface> fonts = new java.util.HashMap<>();
    private final JSONArray recent = new JSONArray();
    private final Runnable closeSoon = this::dismiss;
    private Voice<JSONObject, VoiceAgent.Result> loop;
    private VoiceAgent agent;
    private VoiceActions actions;
    private VoiceOrb orb;
    private TextView status, controls;
    private LinearLayout column, settings;
    private View scrim;
    private final VoiceFocusLease focus = new VoiceFocusLease();
    private int generation;
    private boolean active, listening, cued, launched;
    private long showPreparedAt;
    private boolean firstVisibleDrawPending;

    MaterialVoiceSession(Context context) { super(context); }

    /** HomeKeyService consumes Home itself, so the system may not send onCloseSystemDialogs. */
    static void dismissActive() {
        if (visibleSession != null) visibleSession.dismiss();
    }

    @Override public void onCreate() {
        final long started = SystemClock.elapsedRealtime();
        super.onCreate();
        setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT);
        agent = new VoiceAgent(new File(getContext().getExternalFilesDir(null), "openrouter_key"));
        actions = new VoiceActions(getContext(), intent -> {
            // Start while the assistant is visible (Android's background-launch rules), then hide onFinished.
            getContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            launched = true;
        });
        loop = Voice.builder(getContext())
                .hear(VoiceAssets.capture(getContext()))
                .logTag("MaterialTvVoice")
                .understand(new VoiceStages.Brain(agent))
                .act(new VoiceStages.Actions(actions::run)).build();
        startupTiming("create", started);
    }

    @Override public void onPrepareShow(Bundle args, int flags) {
        showPreparedAt = SystemClock.elapsedRealtime();
        cancelTurn();
        startupTiming("cancel_previous", showPreparedAt);
        final long micStarted = SystemClock.elapsedRealtime();
        // Preserve the working early-capture path, before even the first window is inflated.
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            Voice.atPress(getContext());
        startupTiming("mic_keeper", micStarted);
        super.onPrepareShow(args, flags);
    }

    @Override public View onCreateContentView() {
        final long started = SystemClock.elapsedRealtime();
        Window window = getWindow().getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.TRANSPARENT);
        // Only the lower edge is shaded; the app remains visible around the original floating orb.
        scrim = new View(getContext());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(248), Gravity.BOTTOM));
        column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        int width = Math.min(dp(520), getContext().getResources().getDisplayMetrics().widthPixels - dp(96));
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        columnParams.bottomMargin = dp(24);
        root.addView(column, columnParams);

        orb = new VoiceOrb(getContext());
        orb.setContentDescription("Voice input");
        column.addView(orb, new LinearLayout.LayoutParams(dp(128), dp(128)));
        status = text("One moment…", 22, colors.onSurface);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(width);
        status.setPadding(dp(24), dp(10), dp(24), dp(10));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.topMargin = dp(4);
        column.addView(status, statusParams);
        status.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (status.getBackground() instanceof GradientDrawable)
                ((GradientDrawable) status.getBackground()).setCornerRadius((b - t) / 2f);
        });
        controls = text("", 14, colors.onSurfaceVariant, 300);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-2, -2);
        controlsParams.topMargin = dp(10);
        column.addView(controls, controlsParams);
        settings = new LinearLayout(getContext());
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(10), dp(10), dp(10), dp(10));
        settings.setVisibility(View.GONE);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-1, -2);
        settingsParams.topMargin = dp(12);
        column.addView(settings, settingsParams);
        column.setFocusable(true);
        column.setFocusableInTouchMode(true);
        column.requestFocus();
        applyColors();
        // This measures a draw callback with visible content, not physical key or display-present time.
        root.getViewTreeObserver().addOnDrawListener(() -> {
            if (active && firstVisibleDrawPending && column.getAlpha() > 0f) {
                firstVisibleDrawPending = false;
                startupTiming("first_visible_draw", showPreparedAt);
            }
        });
        startupTiming("create_view", started);
        return root;
    }

    @Override public void onShow(Bundle args, int flags) {
        final long showStarted = SystemClock.elapsedRealtime();
        super.onShow(args, flags);
        active = true;
        firstVisibleDrawPending = true;
        visibleSession = this;
        launched = false;
        settings.setVisibility(View.GONE);
        final String route = selectedRoute();
        final boolean useCloud = "cloud_audio".equals(route);
        updateControls();
        // This context and route belong only to this turn; later shows cannot replace them.
        final long contextStarted = SystemClock.elapsedRealtime();
        final JSONObject context = actions.context(recent);
        startupTiming("context", contextStarted);
        try { context.put("voice_route", route); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        final long paletteStarted = SystemClock.elapsedRealtime();
        styleForApp(context.optString("foreground_app"));
        startupTiming("palette", paletteStarted);
        present();
        startupTiming("show_to_present", showStarted);
        android.util.Log.i("MaterialTvVoice", "assistant show route=" + route);
        if (!agent.hasKey()) { Voice.cancelPress(); message("Add your OpenRouter key using install-voice.sh", 4000); return; }
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Voice.cancelPress(); message("Microphone permission is needed", 4000); return;
        }
        final int turn = generation;
        acquireFocus(turn);
        listening = true;
        cued = false;
        android.util.Log.i("MaterialTvVoice", "pipeline listen=" + route + " surface=assistant local_ready=" + loop.recognizerLoaded());
        loop.listen(new Voice.Listener() {
            private boolean current() { return active && turn == generation && listening; }
            @Override public void onWaiting() {
                if (current()) { status.setText("Connecting microphone…"); orb.setMode(VoiceOrb.WARMING); }
            }
            @Override public void onReady(Voice.Mic mic) {
                if (!current() || (cued && mic != Voice.Mic.REMOTE)) return;
                if (!cued) HomeActivity.beep(true);
                cued = true;
                status.setText("Listening…");
                orb.setMode(VoiceOrb.LISTENING);
            }
            @Override public void onWords(String words) { if (current() && !words.isEmpty()) status.setText(words); }
            @Override public void onLevel(float db) { if (current()) orb.setLevel((db + 60) / 4); }
            @Override public void onThinking() {
                if (current()) { status.setText("Understanding…"); orb.setMode(VoiceOrb.THINKING); }
            }
            @Override public void onHeard(Heard heard) {
                if (!current()) return;
                stopListening();
                HomeActivity.beep(false);
                if (useCloud && (heard.wav == null || heard.wav.length <= 44)) { message("Didn't hear anything", 1500); return; }
                submit(turn, route, context, heard);
            }
            @Override public void onNothing() { if (current()) { stopListening(); message("Didn't hear anything", 1500); } }
            @Override public void onError(String why) {
                if (current()) {
                    stopListening();
                    message(why != null && why.startsWith("Speech recognition") ? why
                            : "Microphone unavailable. Try again", 2500);
                }
            }
        }, !useCloud);
    }

    private void submit(int turn, String route, JSONObject context, Heard heard) {
        final boolean useCloud = "cloud_audio".equals(route);
        status.setText("Thinking…");
        orb.setMode(VoiceOrb.THINKING);
        final long at = System.currentTimeMillis(), started = SystemClock.elapsedRealtime();
        final long[] ready = {started};
        android.util.Log.i("MaterialTvVoice", "pipeline request=" + route + " surface=assistant model=" + modelFor(route)
                + " experimental=true");
        loop.handle(useCloud ? Utterance.audio(heard.wav) : Utterance.text(heard.text), context,
                new Voice.Callback<VoiceAgent.Result>() {
            @Override public boolean onPlan(VoiceAgent.Result result) {
                ready[0] = SystemClock.elapsedRealtime();
                if (!active || turn != generation) return false;
                // Voice.handle calls Act immediately after this callback. Abandon before dispatch,
                // never after a pause/toggle or new-app action. Player callbacks are asynchronous.
                focus.release(turn);
                return result.error == null;
            }
            @Override public void onFinished(VoiceAgent.Result result, String note, boolean acted) {
                long finished = SystemClock.elapsedRealtime();
                android.util.Log.i("MaterialTvVoice", "pipeline finished=" + route + " surface=assistant acted=" + (acted && note == null && result.actions.length() > 0)
                        + " model_ms=" + (ready[0] - started) + " turn_ms=" + (finished - heard.startedElapsedMs));
                record(at, route, heard, result, acted, note, started, ready[0], finished);
                if (!active || turn != generation) return;
                if (result.error != null) {
                    message(result.error.matches(".*OpenRouter said 40[123].*") ? "Check the OpenRouter key"
                            : "Couldn't complete that. Please try again", 3000);
                    return;
                }
                String reply = note != null ? note : result.say.isEmpty() ? "Done" : result.say;
                try {
                    recent.put(new JSONObject().put("said", useCloud ? result.heard : heard.text).put("did", reply));
                    while (recent.length() > 4) recent.remove(0);
                } catch (Exception ignored) { }
                if (launched) dismiss(); else message(reply, 1800);
            }
        });
    }

    private void record(long at, String route, Heard heard, VoiceAgent.Result result, boolean acted, String note,
                        long started, long ready, long finished) {
        try {
            JSONObject firstAction = result.actions.optJSONObject(0);
            JSONObject log = new JSONObject().put("at", at).put("route", route).put("surface", "assistant")
                    .put("model", modelFor(route)).put("experimental", true)
                    .put("interpreter", result.interpreter).put("dispatch_attempted", acted)
                    .put("acted", acted && note == null && result.actions.length() > 0)
                    .put("action_error", note == null ? JSONObject.NULL : note)
                    .put("action_type", firstAction == null ? "" : firstAction.optString("type"))
                    .put("action_package", firstAction == null ? "" : firstAction.optString("package"))
                    .put("action_has_uri", firstAction != null && firstAction.has("uri"))
                    .put("cost", result.cost).put("tokens_in", result.tokensIn).put("tokens_out", result.tokensOut)
                    .put("mic", heard.mic == Voice.Mic.REMOTE ? "remote" : "tv")
                    .put("capture_stats", heard.stats)
                    .put("record_ms", heard.recordMs).put("speech_ms", heard.speechMs)
                    .put("denoise_ms", heard.denoiseMs).put("transcribe_ms", heard.transcribeMs)
                    .put("streaming_decode_ms", heard.streamingDecodeMs)
                    .put("recognition_drain_ms", heard.recognitionDrainMs)
                    .put("recognition_queue_peak_ms", heard.recognitionQueueMs)
                    .put("recognition_decode_burst_ms", heard.recognitionMaxDecodeMs)
                    .put("capture_read_gap_ms", heard.captureReadGapMs)
                    .put("hearing_ms", heard.finishedElapsedMs - heard.startedElapsedMs)
                    .put("model_ms", ready - started).put("action_ms", acted ? finished - ready : 0)
                    .put("turn_ms", finished - heard.startedElapsedMs)
                    .put("error", result.error == null ? JSONObject.NULL : result.error);
            // Measurements only: recordings and spoken words are not written to the assistant log.
            Files.write(new File(getContext().getExternalFilesDir(null), "voice.jsonl").toPath(),
                    (log + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private String selectedRoute() { return "cloud_audio"; }
    private String modelFor(String route) { return VoiceAgent.AUDIO_MODEL; }
    private void updateControls() {
        controls.setText("Back to close · Menu for voice settings");
    }

    private void styleForApp(String pkg) {
        Palette selected = null;
        if (pkg.isEmpty() || pkg.equals(getContext().getPackageName())) selected = HomeActivity.voicePalette();
        else {
            selected = appColors.get(pkg);
            if (selected == null) try {
                PackageManager pm = getContext().getPackageManager();
                Drawable art = pm.getApplicationBanner(pkg);
                if (art == null) art = pm.getApplicationIcon(pkg);
                selected = Palette.of(Palette.seedFrom(art));
                appColors.put(pkg, selected);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        }
        colors = selected != null ? selected : Palette.of(Palette.DEFAULT_SEED);
        applyColors();
    }

    private void applyColors() {
        orb.setColors(colors.primary, colors.primaryContainer, colors.onPrimary, null);
        status.setTextColor(colors.onSurface);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(colors.containerHigh);
        pill.setCornerRadius(status.getHeight() > 0 ? status.getHeight() / 2f : dp(28));
        status.setBackground(pill);
        controls.setTextColor(colors.onSurfaceVariant);
        controls.setShadowLayer(dp(3), 0, dp(1), colors.surface);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {alpha(colors.surface, 0), alpha(colors.surface, 90), alpha(colors.surface, 220)}));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(colors.containerHigh);
        panel.setCornerRadius(dp(28));
        settings.setBackground(panel);
    }

    private void present() {
        column.setAlpha(0f);
        column.animate().alpha(1f).setDuration(Motion.DEFAULT_EFFECTS_MS).setInterpolator(Motion.DEFAULT_EFFECTS).start();
        // Spatial motion stays on the orb, separate from the opacity curve.
        orb.setScaleX(0.82f);
        orb.setScaleY(0.82f);
        orb.setTranslationY(dp(10));
        orb.animate().scaleX(1f).scaleY(1f).translationY(0)
                .setDuration(Motion.DEFAULT_SPATIAL_MS).setInterpolator(Motion.DEFAULT_SPATIAL).start();
    }

    private void settlePresentation() {
        if (column == null) return;
        column.animate().cancel();
        column.setAlpha(1f);
        column.setTranslationY(0);
        orb.animate().cancel();
        orb.setScaleX(1f);
        orb.setScaleY(1f);
        orb.setTranslationY(0);
    }

    private static int alpha(int color, int value) { return (color & 0x00ffffff) | (value << 24); }
    private void message(String words, long delay) {
        focus.release(generation);
        status.setText(words);
        orb.setMode(VoiceOrb.IDLE);
        main.removeCallbacks(closeSoon);
        if (delay > 0) main.postDelayed(closeSoon, delay);
    }
    private void showSettings() {
        cancelTurn();
        status.setText("Gemini voice preview");
        settings.removeAllViews();
        TextView info = text("Recorded audio and TV context go to Gemini through OpenRouter using your key.\nNo screenshots are sent.\nPress the mic to speak, or Back to close.", 17, colors.onSurface);
        info.setPadding(dp(16), dp(10), dp(16), dp(10));
        settings.addView(info, new LinearLayout.LayoutParams(-1, -2));
        settings.setVisibility(View.VISIBLE);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_MENU) { if (event.getRepeatCount() == 0) showSettings(); return true; }
        return super.onKeyDown(key, event);
    }
    @Override public void onBackPressed() { dismiss(); }
    @Override public void onCloseSystemDialogs() { dismiss(); }
    @Override public void onHide() {
        cancelTurn();
        active = false;
        if (visibleSession == this) visibleSession = null;
        android.util.Log.i("MaterialTvVoice", "assistant hidden");
        super.onHide();
    }
    @Override public void onDestroy() {
        cancelTurn();
        active = false;
        if (visibleSession == this) visibleSession = null;
        if (loop != null) loop.release();
        super.onDestroy();
    }
    private void dismiss() {
        // Invalidate immediately; hide() is asynchronous and a model answer could otherwise win the race.
        cancelTurn();
        active = false;
        if (visibleSession == this) visibleSession = null;
        hide();
    }
    private void cancelTurn() {
        firstVisibleDrawPending = false;
        generation++;
        settlePresentation();
        main.removeCallbacks(closeSoon);
        stopListening();
        Voice.cancelPress();
    }
    private void stopListening() {
        stopCapture();
        focus.release();
    }
    private void stopCapture() {
        listening = false;
        if (loop != null) loop.stop();
        if (orb != null) orb.setMode(VoiceOrb.IDLE);
    }
    private void acquireFocus(int turn) {
        AudioManager audio = getContext().getSystemService(AudioManager.class);
        if (audio == null) {
            android.util.Log.i("MaterialTvVoice", "audio_focus granted=false reason=no_manager");
            return;
        }
        try {
            boolean granted = focus.acquire(turn, new VoiceFocusLease.Request() {
                private final AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
                        // Keep existing focus-loss behavior; Back/Home/system dismissal still cancel.
                        .setOnAudioFocusChangeListener(change -> { }, main).build();
                @Override public boolean acquire() {
                    return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                @Override public void abandon() {
                    try { audio.abandonAudioFocusRequest(request); }
                    catch (RuntimeException failure) {
                        android.util.Log.w("MaterialTvVoice", "audio_focus abandon failed");
                    }
                }
            });
            // Focus denial retains the existing ability to listen; it cannot guarantee paused media.
            android.util.Log.i("MaterialTvVoice", "audio_focus granted=" + granted + " gain=may_duck");
        } catch (RuntimeException failure) {
            android.util.Log.w("MaterialTvVoice", "audio_focus request failed");
        }
    }
    private TextView text(String words, float size, int color) {
        return text(words, size, color, 560);
    }
    private TextView text(String words, float size, int color, int weight) {
        TextView view = new TextView(getContext());
        view.setText(words);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        Typeface font = fonts.get(weight);
        if (font == null) {
            try {
                font = new Typeface.Builder(getContext().getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' 24, 'ROND' 0").build();
            } catch (RuntimeException ignored) { font = Typeface.create("sans-serif", Typeface.NORMAL); }
            fonts.put(weight, font);
        }
        view.setTypeface(font);
        return view;
    }
    private void startupTiming(String stage, long started) {
        android.util.Log.i("MaterialTvVoice", "ASSIST_STARTUP stage=" + stage
                + " generation=" + generation + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
    }
    private int dp(float value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
