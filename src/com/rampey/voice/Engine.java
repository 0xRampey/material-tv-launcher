package com.rampey.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hearing stage at work: one listen at a time, on its own thread. Reads 20 ms frames, detects speech (Silero, or
 * loudness against the room's own level), decides when the turn is over, cleans the clip and transcribes it.
 */
final class Engine {
    private static final int RATE = MicKeeper.RATE;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Voice<?, ?> cfg;
    private final Context app;
    private final File modelDir;
    private final long FIRST_WORDS_MS, QUIET_MS, DEADLINE_MS;   // Turns: 6000, 1300, 15000 by default
    private Session current;
    volatile String lastStats = "";

    Engine(Voice<?, ?> cfg) {
        this.cfg = cfg;
        app = cfg.app;
        modelDir = cfg.hear.dir;
        QUIET_MS = cfg.turns.quietMs;
        FIRST_WORDS_MS = cfg.turns.firstWordsMs;
        DEADLINE_MS = cfg.turns.deadlineMs;
    }

    /** One listen: its listener, and whether its events still count. */
    private static final class Session {
        final Voice.Listener listener;
        final boolean transcribe;
        final long listenStartedMs = SystemClock.elapsedRealtime();
        volatile boolean active = true;
        volatile boolean remoteMic;
        volatile StreamingDecoder decoder;
        String fx = "";

        Session(Voice.Listener l, boolean transcribe) {
            listener = l;
            this.transcribe = transcribe;
        }
    }

    private void post(Session s, Runnable r) {
        MAIN.post(() -> { if (s.active) r.run(); });
    }

    void listen(Voice.Listener listener, boolean transcribe) {
        stop();   // a second press mid-listen: finish the old listen first
        Session s = new Session(listener, transcribe);
        current = s;
        boolean fromMicButton = cfg.googleTvRemote && MicKeeper.pressedRecently();
        if (fromMicButton) {
            // The remote's mic takes 1.2-1.8 s to connect after the press. Words said before that land on the faint
            // TV mic, so the listen waits for the remote, and starts fresh then.
            listener.onWaiting();
        } else {
            listener.onReady(Voice.Mic.TV);   // not from a press: the device's own mic, right away
        }
        OfflineHearing.liveStarted();
        new Thread(() -> {
            try { record(s); } finally { OfflineHearing.liveFinished(); }
        }, "voice-record").start();
        // If the remote's mic hasn't taken over by then (no remote voice), it's the TV's own mic.
        if (fromMicButton) MAIN.postDelayed(() -> { if (s.active) listener.onReady(Voice.Mic.TV); }, 2000);
    }

    void stop() {
        if (current != null) {
            current.active = false;
            if (current.decoder != null) current.decoder.cancel();
        }
        current = null;
    }

    /** A started voice recording; if Google's remote service routes the remote's mic to it, say so. */
    private AudioRecord openRecord(Session s, int bytes) {
        // Both of the TV's microphones (its mic reports two channels), at full precision, when it allows; else mono.
        AudioRecord r = null;
        if (cfg.bothMics) try {
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT, bytes);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); r = null; }
        } catch (RuntimeException e) {
            r = null;
        }
        if (r == null) r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
        r.startRecording();
        watchRoute(r, s);
        return r;
    }

    /** If Google's remote service routes the remote's mic to this recording (now or later), say so. */
    private void watchRoute(AudioRecord r, Session s) {
        s.fx = "off";   // the TV's echo canceller kept killing the record track every ~10 s on this chip
        if (!cfg.googleTvRemote) return;
        r.addOnRoutingChangedListener(x -> {
            AudioDeviceInfo d = x.getRoutedDevice();
            if (s.active && d != null && d.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                s.remoteMic = true;
                post(s, () -> s.listener.onReady(Voice.Mic.REMOTE));
            }
        }, MAIN);
        AudioDeviceInfo now = r.getRoutedDevice();
        if (now != null && now.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
            s.remoteMic = true;
            post(s, () -> s.listener.onReady(Voice.Mic.REMOTE));
        }
    }

    private static final class VadFrame {
        final float[] samples;
        final long generation, endMs;
        VadFrame(float[] samples, long generation, long endMs) {
            this.samples = samples;
            this.generation = generation;
            this.endMs = endMs;
        }
    }

    private static final class VadProgress {
        final long generation, endMs, speechMs, firstSpeechMs, lastSpeechMs, completedSpeechEnd;
        final boolean speech;
        VadProgress(long generation, long endMs, boolean speech, long speechMs,
                    long firstSpeechMs, long lastSpeechMs, long completedSpeechEnd) {
            this.generation = generation;
            this.endMs = endMs;
            this.speech = speech;
            this.speechMs = speechMs;
            this.firstSpeechMs = firstSpeechMs;
            this.lastSpeechMs = lastSpeechMs;
            this.completedSpeechEnd = completedSpeechEnd;
        }
    }

    /** Recording owns the mic; recognition and Silero each own their native state on separate workers. */
    private void record(Session s) {
        s.remoteMic = false;
        int frame = RATE / 50;
        int min = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT), frame * 16);
        final int recBytes = Math.max(min * 2, RATE * 2 * 4 * 2);   // ~2 s of stereo-float buffering
        AudioRecord rec;
        // The mic button's assistant session started recording at the press (see MicKeeper): take that one over, with
        // everything it has heard so far. Otherwise start our own.
        final int epoch = MicKeeper.epoch();
        long pressAt = MicKeeper.startedAt(), openedAt = MicKeeper.openedAt();
        AudioRecord adopted = MicKeeper.take();
        // Unlike the audio clock below, this never restarts when the remote route connects.
        final long turnStartedMs = adopted != null ? pressAt : s.listenStartedMs;
        try {
            if (adopted != null) {
                rec = adopted;
                watchRoute(rec, s);
            } else {
                rec = openRecord(s, recBytes);
            }
        } catch (RuntimeException e) {
            if (adopted != null) adopted.release();
            post(s, () -> { s.active = false; s.listener.onError("microphone unavailable"); });
            return;
        }
        boolean stereo = rec.getChannelCount() == 2;
        // Continue the restart count from the press, so an adopted recording doesn't restart in a burst to catch up.
        int restarts = adopted != null ? (int) ((SystemClock.elapsedRealtime() - pressAt) / 400) : 0;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        File asrDir = modelDir;
        // Never wait for the model: while it's still loading, the listen still runs (the caller gets the audio).
        com.k2fsa.sherpa.onnx.OnlineRecognizer asr = s.transcribe ? LocalSpeech.peek() : null;
        final boolean recognizerReady = s.transcribe && (asr != null || LocalSpeech.peekOffline() != null);
        final StreamingDecoder online = asr == null ? null : new StreamingDecoder(new StreamingDecoder.Backend() {
            private com.k2fsa.sherpa.onnx.OnlineStream stream;
            @Override public void create() { stream = asr.createStream(); }
            @Override public void accept(float[] samples) { stream.acceptWaveform(samples, RATE); }
            @Override public boolean ready() { return asr.isReady(stream); }
            @Override public void decode() { asr.decode(stream); }
            @Override public String text() { return asr.getResult(stream).getText().trim(); }
            @Override public void finishInput() { stream.inputFinished(); }
            @Override public void release() {
                if (stream != null) { stream.release(); stream = null; }
            }
        }, () -> s.active && epoch == MicKeeper.epoch(), MAIN::post, s.listener::onWords);
        s.decoder = online;
        float[] samples = new float[frame];
        // Use the same independent speech detector as the successful Gemini capture path.
        com.k2fsa.sherpa.onnx.Vad vad = LocalSpeech.newVad(asrDir);
        float[] vadBuf = new float[512];
        int vadFill = 0;
        boolean vadSpeech = false;
        float vadGain = 4f;
        // Silero runs on its own thread so the microphone is never left waiting (a slow check used to drop audio).
        final LinkedBlockingQueue<VadFrame> vadQ = new LinkedBlockingQueue<>(500);
        final AtomicLong vadGeneration = new AtomicLong();
        final AtomicReference<VadProgress> vadProgress = new AtomicReference<>(new VadProgress(0, 0, false, 0, -1, 0, -1));
        final AtomicReference<String> vadError = new AtomicReference<>();
        long vadSamples = 0;
        final AtomicBoolean vadRun = new AtomicBoolean(true);
        final AtomicLong vadNs = new AtomicLong();
        final com.k2fsa.sherpa.onnx.Vad vadF = vad;
        Thread vadThread = vad == null ? null : new Thread(() -> {
            long generation = 0, speechMs = 0, firstSpeechMs = -1, lastSpeechMs = 0;
            CompletedSpeech completed = new CompletedSpeech();
            try {
                while (vadRun.get() && s.active && epoch == MicKeeper.epoch()) {
                    VadFrame w = vadQ.poll(100, TimeUnit.MILLISECONDS);
                    if (w == null || w.generation != vadGeneration.get()) continue;
                    if (generation != w.generation) {
                        vadF.reset();
                        generation = w.generation;
                        speechMs = 0;
                        firstSpeechMs = -1;
                        lastSpeechMs = 0;
                        completed.reset();
                    }
                    long v0 = System.nanoTime();
                    vadF.acceptWaveform(w.samples);
                    boolean speech = vadF.isSpeechDetected();
                    OfflineHearing.completedSegments(vadF, completed, speech, w.endMs * RATE / 1000);
                    vadNs.addAndGet(System.nanoTime() - v0);
                    if (speech) {
                        long frameMs = w.samples.length * 1000L / RATE;
                        speechMs += frameMs;
                        if (firstSpeechMs < 0) firstSpeechMs = w.endMs - frameMs;
                        lastSpeechMs = w.endMs;
                    }
                    if (w.generation == vadGeneration.get())
                        vadProgress.set(new VadProgress(w.generation, w.endMs, speech,
                                speechMs, firstSpeechMs, lastSpeechMs, completed.end()));
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable failure) {
                vadError.set("Speech recognition couldn't detect speech. Please try again.");
                android.util.Log.w(Voice.tag, "speech detector failed", failure);
            } finally {
                vadF.release();
            }
        }, "voice-vad");
        if (vadThread != null) vadThread.start();
        String localText = "";
        long decodeNs = 0, audioMs = 0;
        boolean endpoint = false;
        String captureError = null;
        long lastReadMs = 0, maxReadGapMs = 0, recognitionDrainMs = -1;
        short[] buf = new short[frame];
        byte[] bytes = new byte[frame * 2];
        float[] both = new float[frame * 2];
        long start = SystemClock.elapsedRealtime(), lastLoud = 0;
        long restartFrom = adopted != null ? pressAt : start;   // the restart window counts from the press
        // The listen's clock is the audio itself (a backlog from the press reads in a burst), counted from the press:
        // an adopted recording began at its last restart, so its first sample is that long after the press.
        long framesRead = adopted != null ? Math.max(0, openedAt - pressAt) * RATE / 1000 : 0;
        int badReads = 0;
        long vadHeardMs = 0;   // how much of the listen Silero called speech
        boolean freshForRemote = false;   // the listen started over when the remote's mic took over
        long skipFrames = 0;              // frames to drop right after the switch (the remote's start-up burst)
        double floor = 0, peak = -90;
        boolean spoke = false;
        int loudFrames = 0;
        ArrayList<Double> early = new ArrayList<>();
        long speechFrom = -1, speechTo = 0;   // byte offsets in pcm where speech starts / was last heard
        long recEnd;
        try {
            // A newer mic press (epoch) ends this listen at once, so its recording never competes with the new one.
            while (s.active && epoch == MicKeeper.epoch()) {
                // Google's remote service only takes a recording that starts after the remote's mic is live; one that
                // was already running is ignored. The two start within ~0.2 s of the press, so start a fresh recording
                // every 0.4 s (for ~3 s) until the service takes one: it then sets up its route (a remote-submix
                // device appears) and the remote's audio follows ~1-2 s later. Restarting after that would tear the
                // route down again, so stop as soon as it's there.
                long since = SystemClock.elapsedRealtime() - restartFrom;
                if (cfg.googleTvRemote && !s.remoteMic && since >= 400L * (restarts + 1) && since < 3000) {
                    if (!MicKeeper.routeUp(app)) {
                        try { rec.stop(); } catch (RuntimeException ignored) { }
                        rec.release();
                        try {
                            rec = openRecord(s, recBytes);
                        } catch (RuntimeException e) {
                            rec = null;
                            captureError = "microphone unavailable";
                            break;
                        }
                        stereo = rec.getChannelCount() == 2;
                        // Keep the clock on the press: the time the restart skipped still passed.
                        framesRead = Math.max(framesRead, (SystemClock.elapsedRealtime() - restartFrom) * RATE / 1000);
                    }
                    restarts++;
                }
                int n;
                if (stereo) {
                    int got = rec.read(both, 0, frame * 2, AudioRecord.READ_BLOCKING);
                    if (got <= 0) { if (++badReads > 50) { captureError = "microphone unavailable"; break; } continue; }
                    n = got / 2;
                    // The pipeline still hears one channel: the two mics averaged (what the TV gave us before).
                    for (int i = 0; i < n; i++) {
                        float m = (both[2 * i] + both[2 * i + 1]) * 0.5f * 32767f;
                        buf[i] = (short) Math.max(-32768, Math.min(32767, Math.round(m)));
                    }
                } else {
                    n = rec.read(buf, 0, frame);
                    if (n <= 0) { if (++badReads > 50) { captureError = "microphone unavailable"; break; } continue; }
                }
                long readAt = SystemClock.elapsedRealtime();
                if (lastReadMs != 0) maxReadGapMs = Math.max(maxReadGapMs, readAt - lastReadMs);
                lastReadMs = readAt;
                badReads = 0;
                if (s.remoteMic && !freshForRemote) {
                    // Everything so far was the TV's faint mic (and the switch-over burst): start the listen over
                    // from this moment, as if the press had just happened.
                    freshForRemote = true;
                    pcm.reset();
                    if (online != null) online.reset();
                    long generation = vadGeneration.incrementAndGet();
                    vadQ.clear();
                    vadProgress.set(new VadProgress(generation, 0, false, 0, -1, 0, -1));
                    vadFill = 0;
                    vadSamples = 0;
                    vadSpeech = false;
                    vadGain = 1f;
                    localText = "";
                    endpoint = false;
                    floor = 0;
                    early.clear();
                    spoke = false;
                    loudFrames = 0;
                    lastLoud = 0;
                    speechFrom = -1;
                    speechTo = 0;
                    peak = -90;
                    vadHeardMs = 0;
                    framesRead = 0;
                    start = SystemClock.elapsedRealtime();
                    skipFrames = RATE * 3 / 10;   // 300 ms
                    post(s, () -> s.listener.onWords(""));
                }
                if (skipFrames > 0) {
                    skipFrames -= n;
                    continue;
                }
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    bytes[2 * i] = (byte) buf[i];
                    bytes[2 * i + 1] = (byte) (buf[i] >> 8);
                }
                pcm.write(bytes, 0, n * 2);
                if (vad != null) {
                    for (int i = 0; i < n; i++) {
                        // The TV's far-field mic is faint at a normal voice: lift it (x4) for the detector. The remote's mic
                        // (routed in by Google's remote service) is already loud: no lift, or it distorts.
                        vadBuf[vadFill++] = Math.max(-1f, Math.min(1f, buf[i] * vadGain / 32768f));
                        if (vadFill == 512) {
                            vadSamples += vadFill;
                            if (!vadQ.offer(new VadFrame(vadBuf.clone(), vadGeneration.get(), vadSamples * 1000 / RATE))) {
                                captureError = "Speech recognition couldn't keep up. Try a shorter command.";
                                break;
                            }
                            vadFill = 0;
                        }
                    }
                    if (vadError.get() != null) captureError = vadError.get();
                }
                if (captureError != null) break;
                if (online != null) {
                    for (int i = 0; i < n; i++) samples[i] = buf[i] / 32768f;
                    // Copies into a bounded queue. No native inference runs on the microphone thread.
                    if (!online.offer(n == frame ? samples : Arrays.copyOf(samples, n))) {
                        captureError = online.error();
                        break;
                    }
                    audioMs += n * 1000L / RATE;
                }
                // One immutable snapshot drives both speech state and endpoint decisions for this frame.
                VadProgress progress = vadProgress.get();
                boolean currentVad = progress.generation == vadGeneration.get();
                vadSpeech = currentVad && progress.speech;
                double db = 10 * Math.log10(sum / n / (32768.0 * 32768.0) + 1e-12);
                if (db > -30) vadGain = 1f;   // a loud source (the remote's mic): stop lifting
                framesRead += n;
                long t = framesRead * 1000 / RATE;
                long now = start + t;
                if (t >= 320 && t < 560) {            // the room's level, after the start chime: a quiet percentile
                    early.add(db);
                    if (vad == null) continue;
                }
                if (floor == 0 && t >= 560) {
                    if (early.isEmpty()) early.add(db);
                    Collections.sort(early);
                    floor = early.get(early.size() / 4);
                }
                if (t < 320 && vad == null) continue;
                boolean loud = db > floor + 9;   // relative to this room's quiet level (normal voices at the TV mic are ~-50 dB)
                if (spoke) {
                    peak = Math.max(peak, db);
                    // Still talking only while clearly above the room and not far below the speaking peak.
                    loud = db > floor + 5 && db > peak - 24;   // softer word endings still count as talking
                } else if (t >= 560 && db < floor + 3) {
                    floor = floor * 0.95 + db * 0.05;   // keep learning the room while no one speaks
                }
                // Silero decides; loudness only helps before speech starts. (The remote's audio stream has a steady hiss
                // well above the room's level, which kept listening going for 10 s.)
                // On the TV's own mic there is no hiss, and a voice across the room is so faint (~-60 dB) that Silero
                // misses parts of it: there, being clearly above the room also counts as still talking.
                // On the remote's mic only Silero may start a listen: its stream opens with a burst that is loud but
                // isn't speech, and that used to end the listen 1.5 s in, before you'd said anything.
                if (vad != null) {
                    // Cumulative timestamps preserve speech even when VAD processes a buffered utterance
                    // between microphone reads. Never infer silence merely from a missed boolean pulse.
                    if (currentVad) {
                        vadHeardMs = progress.speechMs;
                        if (vadHeardMs > 0) {
                            if (!spoke) {
                                spoke = true;
                                peak = db;
                                speechFrom = Math.max(0, progress.firstSpeechMs) * RATE * 2 / 1000;
                            }
                            long clockOffset = t - pcm.size() * 1000L / (RATE * 2);
                            lastLoud = Math.max(lastLoud, start + clockOffset + progress.lastSpeechMs);
                            speechTo = Math.max(speechTo, progress.lastSpeechMs * RATE * 2 / 1000);
                        }
                    }
                    // Preserve the far-field loudness assist; the remote uses Silero alone.
                    loud = !s.remoteMic && t >= 560 && db > floor + (spoke ? 8 : 14);
                }
                loudFrames = loud ? loudFrames + 1 : 0;
                if (!spoke && loudFrames >= (vad != null ? 1 : 2)) { spoke = true; lastLoud = now; peak = db; speechFrom = pcm.size(); }
                else if (loud && spoke) lastLoud = now;
                if (loud && spoke) speechTo = pcm.size();
                final float level = (float) db;
                post(s, () -> s.listener.onLevel(level));
                // A stale VAD result must not cut off speech while its worker catches up.
                boolean detectorCaughtUp = vad == null || (progress.generation == vadGeneration.get()
                        && vadSamples * 1000 / RATE - progress.endMs <= 100);
                // Capture ends on speech timing, never on whether the recognizer has caught up with the words.
                if (detectorCaughtUp && spoke
                        && now - lastLoud > (vad == null ? QUIET_MS : OfflineHearing.quietAfterVad(s.remoteMic))) {
                    if (vad == null || vadHeardMs >= 200) { endpoint = true; break; }
                    spoke = false;
                    speechFrom = -1;
                    loudFrames = 0;
                }
                if (detectorCaughtUp && !spoke && t > FIRST_WORDS_MS) break;
                if (t > DEADLINE_MS || SystemClock.elapsedRealtime() - turnStartedMs > DEADLINE_MS + 3000) {
                    captureError = "Speech recognition reached its listening limit. Try a shorter command.";
                    break;
                }
            }
        } catch (RuntimeException failure) {
            captureError = "microphone unavailable";
            android.util.Log.w(Voice.tag, "capture failed", failure);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (RuntimeException ignored) { } rec.release(); }
            recEnd = SystemClock.elapsedRealtime();
        }
        // Endpoint decisions above retain the original hysteresis. For recognition alone, use a
        // completed segment only if the detector has consumed every submitted window in this route.
        VadProgress finalVad = vadProgress.get();
        long recognitionEnd = CompletedSpeech.trimEnd(Math.max(0, speechFrom / 2), speechTo / 2,
                pcm.size() / 2, finalVad.completedSpeechEnd, s.remoteMic && vad != null,
                endpoint && !finalVad.speech && finalVad.generation == vadGeneration.get()
                        && finalVad.endMs == vadSamples * 1000 / RATE);
        vadRun.set(false);
        vadQ.clear();
        if (vadThread != null) vadThread.interrupt();   // that worker owns native VAD release
        if (online != null) {
            if (!s.active || epoch != MicKeeper.epoch() || captureError != null || !spoke) {
                online.cancel();
            } else {
                post(s, s.listener::onThinking);
                long drainStarted = SystemClock.elapsedRealtime();
                online.finish();
                boolean complete = online.await(6000);
                recognitionDrainMs = SystemClock.elapsedRealtime() - drainStarted;
                if (!complete || online.error() != null) captureError = online.error() == null
                        ? "Speech recognition took too long. Try a shorter command." : online.error();
                else localText = online.text();
            }
            decodeNs = online.decodeMs() * 1000000L;
        }
        if (!s.active || epoch != MicKeeper.epoch()) return;
        if (captureError != null) {
            final String reason = captureError;
            android.util.Log.w(Voice.tag, "listen failed: " + reason);
            post(s, () -> { s.active = false; s.listener.onError(reason); });
            return;
        }
        long transcribeMs = -1, rawMs = -1;
        float boostDb = 0;
        // A stopped listen isn't transcribed: nobody is waiting for its words.
        if (s.transcribe && online == null && spoke && s.active && LocalSpeech.peekOffline() != null) {
            post(s, () -> { if (epoch == MicKeeper.epoch()) s.listener.onThinking(); });
            try {
                byte[] all = pcm.toByteArray();
                float[] samplesForRecognition = new float[all.length / 2];
                for (int i = 0; i < samplesForRecognition.length; i++)
                    samplesForRecognition[i] = (short) ((all[2 * i] & 0xff) | (all[2 * i + 1] << 8)) / 32768f;
                OfflineHearing.Decoded decoded = OfflineHearing.decode(samplesForRecognition,
                        Math.max(0, speechFrom / 2), recognitionEnd, vadHeardMs, peak, floor,
                        asrDir, s.remoteMic && vad != null, () -> s.active && epoch == MicKeeper.epoch());
                localText = decoded.text;
                rawMs = decoded.denoiseMs;
                transcribeMs = decoded.transcribeMs;
                boostDb = decoded.boostDb;
                audioMs = decoded.audioMs;
            } catch (Exception | LinkageError failure) {
                if (!s.active || epoch != MicKeeper.epoch()) return;
                android.util.Log.w(Voice.tag, "offline speech failed: " + failure.getClass().getSimpleName());
                post(s, () -> {
                    if (epoch != MicKeeper.epoch()) return;
                    s.active = false;
                    s.listener.onError("Speech recognition couldn't finish. Please try again.");
                });
                return;
            }
        }
        // Native decode may finish after Back/Home or a newer mic press. Never publish its stale transcript.
        if (!s.active || epoch != MicKeeper.epoch()) return;
        final String spokenText = localText;
        boolean heardSomething = spoke || !spokenText.isEmpty();
        // Nothing is kept: the audio lives only in memory for this listen and is dropped once it's transcribed.
        android.util.Log.i(Voice.tag, "listen ended after " + (SystemClock.elapsedRealtime() - start)
                + " ms: local=" + (asr != null) + " text='" + spokenText + "' endpoint=" + endpoint + " spoke=" + spoke
                + " still_listening=" + s.active + " current=" + (s == current)
                + " capture_ms=" + (recEnd - turnStartedMs) + " drain_ms=" + recognitionDrainMs
                + " queue_peak_ms=" + (online == null ? -1 : online.peakQueueMs())
                + " decode_burst_ms=" + (online == null ? -1 : online.maxDecodeMs())
                + " read_gap_ms=" + maxReadGapMs);
        String stats = "mic=" + (s.remoteMic ? "remote" : "tv") + " from_press=" + (adopted != null) + " restarts=" + restarts + " ch=" + (stereo ? 2 : 1) + " fx=" + s.fx.replace(' ', '+') + " rec_ms=" + (recEnd - turnStartedMs) + " floor=" + Math.round(floor)
                + " peak=" + Math.round(peak)
                + (asr != null ? " asr=local rtf=" + String.format(Locale.ROOT, "%.2f", audioMs == 0 ? 0 : decodeNs / 1e6 / audioMs)
                + " load_ms=" + LocalSpeech.loadMs + " vad=" + (vad != null ? "silero" : "loudness")
                + " drain_ms=" + recognitionDrainMs + " queue_peak_ms=" + online.peakQueueMs()
                + " decode_burst_ms=" + online.maxDecodeMs() + " read_gap_ms=" + maxReadGapMs
                : transcribeMs >= 0 ? " asr=" + (LocalSpeech.loaded != null ? LocalSpeech.loaded.id() : "?") + " transcribe_ms=" + transcribeMs + " audio_ms=" + audioMs + " load_ms=" + LocalSpeech.loadMs
                + " boost_db=" + Math.round(boostDb) + " denoise_ms=" + rawMs
                + " asr_tail_trim_ms=" + Math.max(0, (speechTo / 2 - recognitionEnd) * 1000L / RATE) + " vad=" + (vad != null ? "silero" : "loudness")
                + " captured_ms=" + (pcm.size() / 2 * 1000L / RATE) + " vad_ms_per_s=" + (audioMs == 0 ? 0 : vadNs.get() / 1000000L * 1000L / Math.max(1, audioMs))
                : " asr=" + (s.transcribe ? "unavailable" : "skipped")
                + " vad=" + (vad != null ? "silero" : "loudness"));
        lastStats = stats;
        byte[] wav = AudioTools.wav(pcm.toByteArray(), RATE);
        Voice.Mic mic = s.remoteMic ? Voice.Mic.REMOTE : Voice.Mic.TV;
        long recordMs = recEnd - turnStartedMs, denoiseMs = rawMs, transcribedMs = transcribeMs;
        long speechMs = speechFrom < 0 ? 0 : (speechTo - speechFrom) * 1000L / (RATE * 2);
        long streamingDecodeMs = asr == null ? -1 : decodeNs / 1000000L;
        final Heard heard = new Heard(spokenText, mic, speechMs, recordMs, denoiseMs, transcribedMs,
                recognizerReady, wav, stats, turnStartedMs, SystemClock.elapsedRealtime(), streamingDecodeMs,
                recognitionDrainMs, online == null ? -1 : online.peakQueueMs(),
                online == null ? -1 : online.maxDecodeMs(), maxReadGapMs);
        post(s, () -> {
            if (epoch != MicKeeper.epoch()) return;
            s.active = false;   // the listen is over: a late mic hint can't arrive after its result
            if (!heardSomething) s.listener.onNothing();
            else s.listener.onHeard(heard);
        });
    }
}
