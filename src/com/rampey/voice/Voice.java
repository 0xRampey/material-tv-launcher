package com.rampey.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Local speech to text for Android, and a voice loop around it: Hear -> Understand -> Act -> Reply (the Pipecat /
 * Picovoice shape). Hearing ships and runs entirely on the device: the microphone, Silero voice detection,
 * turn-taking, clean-up (100 Hz high-pass, GTCRN denoise, gain) and a sherpa-onnx recognizer. Understand, Act and
 * Reply are interfaces for the app; each is optional.
 *
 * <pre>
 * Voice voice = Voice.create(context);           // models from files/asr, loading in the background
 * voice.listen(text -&gt; show(text));            // one utterance
 *
 * Voice&lt;Ctx, Plan&gt; loop = Voice.builder(context)
 *         .hear(Hear.auto(dir)).turns(Turns.silence(1300))
 *         .understand(brain).act(actions).reply(speaker)
 *         .build();
 * </pre>
 *
 * The app holds RECORD_AUDIO and handles audio focus. One listen at a time per process. Built and tuned on Android TV
 * (Google TV); the TV-specific parts are marked as such.
 */
public final class Voice<C, P> {
    static volatile String tag = "Voice";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Which microphone hears the person. */
    public enum Mic { REMOTE, TV }

    /** Events of one listen, all on the main thread; override what you need. None arrive after {@link #stop()}. */
    public abstract static class Listener {
        /** The listen started from a mic-button press ({@link #atPress}) and waits for the remote's mic to connect
         *  (~1.2-1.8 s; Android TV). {@link #onReady} follows. */
        public void onWaiting() { }

        /** Speak now: this mic hears you. TV may come first and REMOTE later, when the remote's mic connects (the listen
         *  then starts over, so nothing said into the faint TV mic is mixed in). */
        public void onReady(Mic mic) { }

        /** Live words so far, from a streaming model ("" when the listen starts over). Whole-clip models send none. */
        public void onWords(String soFar) { }

        /** The input level of the last 20 ms frame, in dBFS (about -90 to 0). */
        public void onLevel(float db) { }

        /** The person stopped talking and a whole-clip model is transcribing (a second or more on a slow chip). */
        public void onThinking() { }

        /** Speech was heard; {@link Heard#text} is "" when it couldn't be made out. */
        public void onHeard(Heard heard) { }

        /** Nothing that sounded like speech before the first-words timeout. */
        public void onNothing() { }

        /** Capture or recognition failed (or this Voice was released). Speech recognition errors include a user-facing reason. */
        public void onError(String why) { }
    }

    /** One utterance's text: the transcript, or "" if nothing was understood (or the mic failed). */
    public interface OnText {
        void onText(String text);
    }

    /** Main-thread hooks around Act and Reply, for {@link #handle}. */
    public interface Callback<P> {
        /** The plan is ready. Return false to skip Act and Reply (the request went stale, say). */
        default boolean onPlan(P plan) { return true; }

        /** Done. acted: Act and Reply ran; note: what Act returned. */
        default void onFinished(P plan, String note, boolean acted) { }
    }

    final Context app;
    final Hear hear;
    final Turns turns;
    final boolean googleTvRemote, bothMics;
    private final Understand<C, P> understand;
    private final Act<P> act;
    private final Reply<P> reply;
    private final Engine engine;
    private volatile boolean released;

    private Voice(Builder<C, P> b) {
        app = b.context.getApplicationContext();
        hear = b.hear != null ? b.hear : Hear.auto(new File(b.context.getExternalFilesDir(null), "asr"));
        turns = b.turns != null ? b.turns : Turns.defaults();
        googleTvRemote = b.googleTvRemote;
        bothMics = b.bothMics;
        understand = b.understand;
        act = b.act;
        reply = b.reply;
        if (b.logTag != null) tag = b.logTag;
        engine = new Engine(this);
    }

    /** All defaults: models from getExternalFilesDir(null)/asr (picked by the files present), loading now. */
    public static Voice<Object, Object> create(Context context) {
        Voice<Object, Object> v = builder(context).build();
        v.preloadAsync("voice-load");
        return v;
    }

    public static Builder<Object, Object> builder(Context context) {
        return new Builder<>(context);
    }

    /**
     * Android TV: call at the mic-button press, from a VoiceInteractionSession's onShow, before your listening screen
     * is up. It starts recording at once (restarting until Google's remote service routes the remote's mic to it) and
     * the next {@link #listen} takes that recording over, with everything said since the press.
     */
    public static void atPress(Context context) {
        MicKeeper.start(context);
    }

    /** Cancel a mic-button recording that the listening screen has not taken over yet. */
    public static void cancelPress() {
        android.media.AudioRecord pending = MicKeeper.take();
        if (pending == null) return;
        try { pending.stop(); } catch (RuntimeException ignored) { }
        pending.release();
    }

    /** Frees the loaded models (they're shared by every Voice in the process). Not while a listen is running. */
    public static void unloadModels() {
        LocalSpeech.unload();
    }

    /** A recognizer and its tokens.txt are in the model folder. */
    public boolean isInstalled() {
        return LocalSpeech.installed(hear.dir, hear.model);
    }

    /** Loads the recognizer now (blocking, off the main thread). Once per process; later calls return at once. */
    public void preload() {
        LocalSpeech.get(hear.dir, hear.model);
    }

    /** {@link #preload} on a new thread, if the models are installed. */
    public void preloadAsync(String threadName) {
        if (isInstalled()) new Thread(this::preload, threadName).start();
    }

    /** A recognizer is loaded (a listen now transcribes on the device). A listen before that hears, but can't. */
    public boolean recognizerLoaded() {
        return LocalSpeech.peekOffline() != null || LocalSpeech.peek() != null;
    }

    /** Why the recognizer couldn't load, or null. */
    public String loadError() {
        return LocalSpeech.error;
    }

    /** The {@link Heard#stats} line of the most recent listen, including one that was stopped. */
    public String lastStats() {
        return engine.lastStats;
    }

    /** Start listening (stops a listen already running). Call on the main thread. */
    public void listen(Listener listener) {
        listen(listener, true);
    }

    /** One listen, with optional on-device transcription. False still records and detects speech, but never uses
     *  a cached speech recognizer; the caller chooses what to do with the resulting WAV. */
    public void listen(Listener listener, boolean transcribe) {
        if (released) {
            listener.onError("released");
            return;
        }
        engine.listen(listener, transcribe);
    }

    /** One utterance, as text. */
    public void listen(OnText onText) {
        listen(new Listener() {
            @Override public void onHeard(Heard heard) { onText.onText(heard.text); }
            @Override public void onNothing() { onText.onText(""); }
            @Override public void onError(String why) { onText.onText(""); }
        });
    }

    public static final class ReplayResult {
        public String text = "", status = "failed";
        public long inputMs, endpointMs, speechMs, processedAudioMs, captureWallMs,
                preprocessMs, transcribeMs, totalWallMs, vadMs, denoiseMs = -1;
    }

    /** Stop the current listen: the mic is released and no more events arrive. */
    public void stop() {
        engine.stop();
    }

    /** Stop, and refuse further listens. The models stay loaded for the process (see {@link #unloadModels}). */
    public void release() {
        released = true;
        stop();
    }

    /** Understand on a background thread, then Act and Reply on the main thread. Without an Understand stage the plan
     *  is null. */
    public void handle(Utterance utterance, C context, Callback<P> callback) {
        new Thread(() -> {
            P plan = understand == null ? null : understand.understand(utterance, context);
            MAIN.post(() -> {
                boolean go = callback == null || callback.onPlan(plan);
                String note = null;
                if (go && act != null) note = act.act(plan);
                if (go && reply != null) reply.reply(plan, note);
                if (callback != null) callback.onFinished(plan, note, go);
            });
        }, "voice").start();
    }

    public static final class Builder<C, P> {
        private final Context context;
        private Hear hear;
        private Turns turns;
        private boolean googleTvRemote = true, bothMics = true;
        private Understand<C, P> understand;
        private Act<P> act;
        private Reply<P> reply;
        private String logTag;

        private Builder(Context context) {
            this.context = context;
        }

        /** The speech-to-text model (default {@link Hear#auto} on getExternalFilesDir(null)/asr). */
        public Builder<C, P> hear(Hear h) {
            hear = h;
            return this;
        }

        /** When a turn ends (default {@link Turns#defaults}). */
        public Builder<C, P> turns(Turns t) {
            turns = t;
            return this;
        }

        /**
         * Android TV / Google TV: hear the remote's mic (default on). Google's remote service routes the remote's
         * audio only to a recording that starts after the remote's mic is live, so for the first 3 s a listen restarts
         * its recording every 0.4 s until the route appears, and never after. Off: the device mic only, no restarts.
         */
        public Builder<C, P> googleTvRemote(boolean on) {
            googleTvRemote = on;
            return this;
        }

        /** Android TV: record the TV's two far-field mics as stereo float and average them (default on; falls back to
         *  mono 16-bit when the device refuses). Off: mono 16-bit from the start. */
        public Builder<C, P> bothMics(boolean on) {
            bothMics = on;
            return this;
        }

        /** Stage 2. Set it before {@link #act} and {@link #reply}: it fixes the context and plan types. */
        @SuppressWarnings("unchecked")
        public <C2, P2> Builder<C2, P2> understand(Understand<C2, P2> u) {
            Builder<C2, P2> b = (Builder<C2, P2>) (Builder<?, ?>) this;
            b.understand = u;
            return b;
        }

        /** Stage 3. */
        public Builder<C, P> act(Act<P> a) {
            act = a;
            return this;
        }

        /** Stage 4. */
        public Builder<C, P> reply(Reply<P> r) {
            reply = r;
            return this;
        }

        /** The logcat tag (default "Voice"). */
        public Builder<C, P> logTag(String t) {
            logTag = t;
            return this;
        }

        public Voice<C, P> build() {
            return new Voice<>(this);
        }
    }
}
