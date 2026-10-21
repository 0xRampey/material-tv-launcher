package com.rampey.voice;

import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.Vad;
import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/** Shared whole-clip processing, and a lab replay of the remote audio timeline. */
final class OfflineHearing {
    static final int RATE = 16000;
    private static int live, replays;
    private static long generation;

    static synchronized void liveStarted() { live++; generation++; }
    static synchronized void liveFinished() { live--; }
    private static synchronized long reserve() {
        if (live != 0 || replays != 0) return -1;
        replays++;
        return ++generation;
    }
    private static synchronized boolean current(long ticket) {
        return generation == ticket && live == 0;
    }
    private static synchronized void release() { replays--; }

    static long quietAfterVad(boolean remote) { return remote ? 200 : 1000; }
    static int postPadSamples(boolean remote) { return remote ? RATE * 120 / 1000 : RATE / 2; }

    /** A completed segment excludes Silero's silence hold; isSpeechDetected() includes that hold.
     * Only timestamps are retained. Unsupported segment APIs leave the existing bounds in use. */
    static void completedSegments(Vad vad, CompletedSpeech bounds, boolean speech, long processedSamples) {
        bounds.observe(speech, processedSamples);
        if (!bounds.available()) return;
        try {
            int drained = 0;
            while (!vad.empty()) {
                if (++drained > 32) { bounds.unavailable(); return; }
                com.k2fsa.sherpa.onnx.SpeechSegment segment = vad.front();
                float[] samples = segment == null ? null : segment.getSamples();
                if (samples == null) bounds.unavailable();
                else bounds.completed(segment.getStart(), samples.length);
                vad.pop();
            }
        } catch (RuntimeException | LinkageError unsupported) {
            bounds.unavailable();
        }
    }

    static final class Decoded {
        String text = "";
        long audioMs, preprocessMs, denoiseMs = -1, transcribeMs;
        float boostDb;
    }

    static Decoded decode(float[] all, long speechFrom, long speechTo, long vadSpeechMs,
                          double peak, double floor, File modelDir, boolean remote,
                          BooleanSupplier active) {
        long started = SystemClock.elapsedRealtime();
        Decoded result = new Decoded();
        if (!active.getAsBoolean()) return result;
        int from = (int) Math.max(0, speechFrom - RATE * 4 / 10);
        int to = (int) Math.min(all.length, Math.max(speechTo, from) + postPadSamples(remote));
        float[] clip = Arrays.copyOfRange(all, from, to);
        result.audioMs = clip.length * 1000L / RATE;
        AudioTools.highPass(clip, 100f, RATE);
        long denoiseStart = SystemClock.elapsedRealtime();
        boolean needsCleanup = peak - floor < 35 || peak < -35;
        float[] clean = needsCleanup ? LocalSpeech.denoise(clip, RATE, modelDir) : null;
        result.denoiseMs = clean == null ? -1 : SystemClock.elapsedRealtime() - denoiseStart;
        float[] use = clean == null ? clip : clean;
        float peakAbs = 1e-6f;
        for (float value : use) peakAbs = Math.max(peakAbs, Math.abs(value));
        float gain = Math.min(0.708f / peakAbs, 63f);
        float[] boosted = new float[use.length];
        for (int i = 0; i < use.length; i++)
            boosted[i] = Math.max(-1f, Math.min(1f, use[i] * gain));
        result.boostDb = (float) (20 * Math.log10(gain));
        long speechMs = Math.max(0, speechTo - speechFrom) * 1000 / RATE;
        boolean enough = vadSpeechMs >= 300 || (speechMs >= 300 && peak - floor >= 10);
        result.preprocessMs = SystemClock.elapsedRealtime() - started;
        long recognitionStart = SystemClock.elapsedRealtime();
        if (enough && active.getAsBoolean())
            result.text = AudioTools.collapseRepeats(LocalSpeech.transcribe(boosted, RATE, active));
        result.transcribeMs = SystemClock.elapsedRealtime() - recognitionStart;
        if (!active.getAsBoolean()) result.text = "";
        return result;
    }

    static Voice.ReplayResult replay(Voice<?, ?> voice, float[] pcm, boolean paced, BooleanSupplier caller) {
        Voice.ReplayResult out = new Voice.ReplayResult();
        if (pcm == null || pcm.length == 0 || pcm.length > RATE * 15 || caller == null) {
            out.status = "invalid_input"; return out;
        }
        for (float value : pcm) if (!Float.isFinite(value) || Math.abs(value) > 1f) {
            out.status = "invalid_input"; return out;
        }
        if (LocalSpeech.peekOffline() == null) { out.status = "unavailable"; return out; }
        long ticket = reserve();
        if (ticket < 0) { out.status = "busy"; return out; }
        BooleanSupplier active = () -> current(ticket) && caller.getAsBoolean();
        Vad vad = null;
        long started = SystemClock.elapsedRealtime();
        out.inputMs = pcm.length * 1000L / RATE;
        try {
            vad = LocalSpeech.newVad(voice.hear.dir);
            if (vad == null) { out.status = "unavailable"; return out; }
            // 20-ms frames match AudioRecord; feed the same 512-sample Silero windows.
            float[] all = Arrays.copyOf(pcm, Math.min(RATE * 18, pcm.length + RATE * 2));
            float[] window = new float[512];
            int fill = 0, consumed = 0, vadSamples = 0;
            long first = -1, last = 0, vadSpeechMs = 0, vadNs = 0;
            CompletedSpeech completed = new CompletedSpeech();
            double floor = 0, peak = -90;
            ArrayList<Double> early = new ArrayList<>();
            boolean endpoint = false;
            while (consumed < all.length && active.getAsBoolean()) {
                int count = Math.min(RATE / 50, all.length - consumed);
                long arrival = (consumed + count) * 1000L / RATE;
                if (paced) {
                    long remaining = started + arrival - SystemClock.elapsedRealtime();
                    while (remaining > 0 && active.getAsBoolean()) {
                        Thread.sleep(Math.min(remaining, 20));
                        remaining = started + arrival - SystemClock.elapsedRealtime();
                    }
                }
                if (!active.getAsBoolean()) break;
                double sum = 0;
                for (int i = consumed; i < consumed + count; i++) {
                    float value = all[i];
                    sum += value * value;
                    window[fill++] = value;
                    if (fill == window.length) {
                        long v0 = System.nanoTime();
                        vad.acceptWaveform(window);
                        boolean speech = vad.isSpeechDetected();
                        vadNs += System.nanoTime() - v0;
                        vadSamples += fill;
                        completedSegments(vad, completed, speech, vadSamples);
                        if (speech) {
                            vadSpeechMs += fill * 1000L / RATE;
                            if (first < 0) first = vadSamples - fill;
                            last = vadSamples;
                        }
                        fill = 0;
                    }
                }
                consumed += count;
                double db = 10 * Math.log10(sum / count + 1e-12);
                if (arrival >= 320 && arrival < 560) early.add(db);
                if (floor == 0 && arrival >= 560) {
                    if (early.isEmpty()) early.add(db);
                    Collections.sort(early);
                    floor = early.get(early.size() / 4);
                }
                if (first >= 0) peak = Math.max(peak, db);
                boolean caughtUp = (consumed - vadSamples) * 1000L / RATE <= 100;
                if (caughtUp && first >= 0 && vadSpeechMs >= 200
                        && arrival - last * 1000L / RATE > quietAfterVad(true)) {
                    endpoint = true; break;
                }
                if (first < 0 && arrival > voice.turns.firstWordsMs) break;
                if (arrival > voice.turns.deadlineMs) break;
            }
            out.captureWallMs = SystemClock.elapsedRealtime() - started;
            out.endpointMs = consumed * 1000L / RATE;
            out.speechMs = first < 0 ? 0 : (last - first) * 1000L / RATE;
            out.vadMs = vadNs / 1_000_000;
            if (!active.getAsBoolean()) { out.status = "cancelled"; return out; }
            if (!endpoint) {
                out.status = first < 0 ? "no_speech" : "listening_limit"; return out;
            }
            long recognitionEnd = CompletedSpeech.trimEnd(first, last, consumed,
                    completed.end(), true, true);
            Decoded decoded = decode(Arrays.copyOf(all, consumed), first, recognitionEnd, vadSpeechMs,
                    peak, floor, voice.hear.dir, true, active);
            out.text = decoded.text;
            out.processedAudioMs = decoded.audioMs;
            out.preprocessMs = decoded.preprocessMs;
            out.denoiseMs = decoded.denoiseMs;
            out.transcribeMs = decoded.transcribeMs;
            out.status = active.getAsBoolean() ? "ok" : "cancelled";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            out.status = "cancelled";
        } catch (Exception | LinkageError failed) {
            out.status = "failed";
            android.util.Log.w(Voice.tag, "replay failed: " + failed.getClass().getSimpleName());
        } finally {
            try { if (vad != null) vad.release(); } finally {
                out.totalWallMs = SystemClock.elapsedRealtime() - started;
                release();
            }
        }
        return out;
    }
}
