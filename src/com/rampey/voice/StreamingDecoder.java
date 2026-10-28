package com.rampey.voice;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One listen's ordered, bounded audio queue. Only its worker touches the native stream. */
final class StreamingDecoder {
    interface Backend {
        void create();
        void accept(float[] samples);
        boolean ready();
        void decode();
        String text();
        void finishInput();
        void release();
    }

    private static final int RATE = 16000;
    private static final int MAX_FRAMES = 800;
    private static final int MAX_SAMPLES = RATE * 16;
    // A cancelled session can still be inside a native decode. Never run the next session alongside it.
    private static final Object DECODE_LOCK = new Object();
    private final Object state = new Object();
    private final ArrayDeque<float[]> queue = new ArrayDeque<>();
    private final Backend backend;
    private final BooleanSupplier active;
    private final Consumer<Runnable> dispatch;
    private final Consumer<String> onWords;
    private long generation;
    private int queuedSamples;
    private boolean finishing, cancelled, done;
    private volatile String text = "", error;
    private volatile long decodeNs, maxDecodeNs, peakQueuedSamples;

    StreamingDecoder(Backend backend, BooleanSupplier active, Consumer<Runnable> dispatch,
                     Consumer<String> onWords) {
        this.backend = backend;
        this.active = active;
        this.dispatch = dispatch;
        this.onWords = onWords;
        new Thread(this::run, "voice-asr").start();
    }

    /** Never waits for recognition. A full queue fails the listen instead of losing speech. */
    boolean offer(float[] samples) {
        if (samples == null || samples.length == 0) return true;
        float[] copy = samples.clone();
        synchronized (state) {
            if (cancelled || done || finishing || !active.getAsBoolean()) return false;
            if (queue.size() >= MAX_FRAMES || copy.length > MAX_SAMPLES - queuedSamples) {
                failLocked("Speech recognition fell behind. Please try again");
                return false;
            }
            queue.addLast(copy);
            queuedSamples += copy.length;
            peakQueuedSamples = Math.max(peakQueuedSamples, queuedSamples);
            state.notifyAll();
            return true;
        }
    }

    /** Remote-mic takeover: discard the old input and invalidate even already-posted partial words. */
    void reset() {
        synchronized (state) {
            if (cancelled || done || finishing) return;
            generation++;
            queue.clear();
            queuedSamples = 0;
            text = "";
            state.notifyAll();
        }
    }

    void finish() {
        synchronized (state) {
            if (cancelled || done) return;
            finishing = true;
            state.notifyAll();
        }
    }

    void cancel() {
        synchronized (state) {
            cancelLocked();
        }
    }

    /** Wait off the capture thread, after it has closed the microphone. */
    boolean await(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        synchronized (state) {
            while (!done) {
                if (cancelled) return false;
                if (!active.getAsBoolean()) {
                    cancelLocked();
                    return false;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    failLocked("Speech recognition took too long. Please try again");
                    return false;
                }
                try {
                    state.wait(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelLocked();
                    return false;
                }
            }
            return !cancelled && error == null && active.getAsBoolean();
        }
    }

    String text() { return text; }
    String error() { return error; }
    long decodeMs() { return TimeUnit.NANOSECONDS.toMillis(decodeNs); }
    long maxDecodeMs() { return TimeUnit.NANOSECONDS.toMillis(maxDecodeNs); }
    long peakQueueMs() { return peakQueuedSamples * 1000L / RATE; }

    private boolean current(long expected) {
        synchronized (state) {
            return !cancelled && generation == expected && active.getAsBoolean();
        }
    }

    private void cancelLocked() {
        if (!cancelled) generation++;
        cancelled = true;
        text = "";
        queue.clear();
        queuedSamples = 0;
        state.notifyAll();
    }

    private void failLocked(String why) {
        if (error == null) error = why;
        cancelLocked();
    }

    private boolean drain(long expected) {
        while (current(expected)) {
            if (!backend.ready()) return current(expected);
            synchronized (DECODE_LOCK) {
                // stop/reset can happen while a previous session owns this lock.
                if (!current(expected)) return false;
                long start = System.nanoTime();
                try {
                    backend.decode();
                } finally {
                    long elapsed = System.nanoTime() - start;
                    decodeNs += elapsed;
                    maxDecodeNs = Math.max(maxDecodeNs, elapsed);
                }
            }
        }
        return false;
    }

    private void publish(long expected) {
        if (!current(expected)) return;
        final String words = backend.text().trim();
        synchronized (state) {
            if (!current(expected) || words.equals(text)) return;
            text = words;
        }
        dispatch.accept(() -> {
            synchronized (state) {
                if (current(expected)) onWords.accept(words);
            }
        });
    }

    private void run() {
        long streamGeneration = -1;
        boolean created = false;
        try {
            while (true) {
                long expected;
                float[] samples = null;
                boolean recreate, endInput = false;
                synchronized (state) {
                    while (true) {
                        if (cancelled || !active.getAsBoolean()) {
                            cancelLocked();
                            return;
                        }
                        expected = generation;
                        recreate = streamGeneration != expected;
                        if (recreate) break;
                        if (!queue.isEmpty()) {
                            samples = queue.removeFirst();
                            queuedSamples -= samples.length;
                            break;
                        }
                        if (finishing) {
                            endInput = true;
                            break;
                        }
                        state.wait(100);
                    }
                }
                if (recreate) {
                    if (created) {
                        created = false;
                        backend.release();
                    }
                    if (!current(expected)) continue;
                    // release is safe even if create throws after partially allocating a stream.
                    created = true;
                    backend.create();
                    streamGeneration = expected;
                    continue;
                }
                if (!current(expected)) continue;
                if (endInput) {
                    backend.finishInput();
                    if (!drain(expected)) continue;
                    publish(expected);
                    if (current(expected)) return;
                } else {
                    backend.accept(samples);
                    if (drain(expected)) publish(expected);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel();
        } catch (Throwable e) {
            synchronized (state) {
                if (!cancelled && active.getAsBoolean()) {
                    failLocked("Speech recognition could not finish. Please try again");
                }
            }
        } finally {
            if (created) {
                try {
                    backend.release();
                } catch (Throwable e) {
                    synchronized (state) {
                        if (!cancelled) failLocked("Speech recognition could not finish. Please try again");
                    }
                }
            }
            synchronized (state) {
                done = true;
                state.notifyAll();
            }
        }
    }
}
