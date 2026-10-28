package com.rampey.voice;

/** Timestamp-only tracking of completed VAD segments; audio remains in the capture buffer. */
final class CompletedSpeech {
    private static final long MAX_SAMPLES = 16000L * 18;
    private long processed, end = -1;
    private boolean speaking, available = true;

    void reset() { processed = 0; end = -1; speaking = false; available = true; }

    void observe(boolean speech, long processedSamples) {
        if (processedSamples < processed || processedSamples < 0 || processedSamples > MAX_SAMPLES) {
            unavailable();
            return;
        }
        processed = processedSamples;
        speaking = speech;
        // A previous finished segment never bounds a resumed, unfinished utterance.
        if (speech) end = -1;
    }

    void completed(long start, long count) {
        if (!available || speaking) return;
        if (start < 0 || count <= 0 || count > MAX_SAMPLES || start > processed
                || count > processed - start) {
            unavailable();
            return;
        }
        long next = start + count;
        if (end >= 0 && next < end) { unavailable(); return; }
        end = next;
    }

    void unavailable() { available = false; end = -1; }
    boolean available() { return available; }
    long end() { return available && !speaking ? end : -1; }

    /** Only trim the remote's final tail. Never alter preroll, internal pauses, or a fallback bound. */
    static long trimEnd(long first, long fallbackEnd, long capturedSamples, long completedEnd,
                        boolean remote, boolean detectorCaughtUp) {
        return remote && detectorCaughtUp && first >= 0 && completedEnd > first
                && completedEnd <= fallbackEnd && completedEnd <= capturedSamples
                ? completedEnd : fallbackEnd;
    }
}
