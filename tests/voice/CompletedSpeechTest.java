package com.rampey.voice;

/** Completed-VAD trimming regressions: no Android/native model or device required. */
public final class CompletedSpeechTest {
    private static int checks;
    private static void equal(long expected, long actual, String message) {
        checks++;
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private static long trim(CompletedSpeech bounds) {
        return CompletedSpeech.trimEnd(2400, 44000, 48000, bounds.end(), true, true);
    }
    public static void main(String[] args) {
        CompletedSpeech bounds = new CompletedSpeech();
        equal(44000, trim(bounds), "no completed segment preserves old bound");
        bounds.observe(true, 8000);
        equal(44000, trim(bounds), "unfinished first segment preserves old bound");
        bounds.observe(false, 25600);
        bounds.completed(1000, 15000);
        equal(16000, bounds.end(), "completed endpoint excludes detector silence hold");
        equal(16000, trim(bounds), "remote recognition trims to completed endpoint");
        bounds.observe(false, 27000);
        equal(16000, trim(bounds), "additional silence does not extend completed speech");

        bounds.observe(true, 28000);
        equal(44000, trim(bounds), "resumed speech invalidates earlier completion immediately");
        bounds.completed(1000, 15000);
        equal(44000, trim(bounds), "queued old segment cannot bound active speech");
        bounds.observe(false, 30000);
        equal(44000, trim(bounds), "missing completion after resumed speech uses fallback");
        bounds.completed(27000, 2000);
        equal(29000, trim(bounds), "second completion retains continuous audio through second segment");
        // Only the final end moves; the original first speech/preroll and entire internal pause remain.
        equal(29000, CompletedSpeech.trimEnd(1000, 44000, 48000, bounds.end(), true, true),
                "first segment and internal pause stay inside the original continuous clip");
        equal(44000, CompletedSpeech.trimEnd(2400, 44000, 48000, bounds.end(), false, true),
                "TV microphone uses original far-field bounds");
        equal(44000, CompletedSpeech.trimEnd(2400, 44000, 48000, bounds.end(), true, false),
                "pending detector windows prevent trimming");
        equal(44000, CompletedSpeech.trimEnd(30000, 44000, 48000, bounds.end(), true, true),
                "completed bound before first speech is rejected");
        equal(44000, CompletedSpeech.trimEnd(-1, 44000, 48000, bounds.end(), true, true),
                "missing first speech is rejected");
        equal(44000, CompletedSpeech.trimEnd(2400, 44000, 28000, bounds.end(), true, true),
                "completed bound beyond captured audio is rejected");
        equal(20000, CompletedSpeech.trimEnd(2400, 20000, 48000, bounds.end(), true, true),
                "completed data never extends the old bound");

        bounds.unavailable();
        equal(44000, trim(bounds), "unavailable segment API falls back");
        bounds.observe(false, 32000);
        bounds.completed(27000, 3000);
        equal(44000, trim(bounds), "unavailable API stays disabled for this listen");
        bounds.reset();
        equal(44000, trim(bounds), "route reset drops every old completion");
        bounds.observe(false, 10000);
        bounds.completed(1000, 5000);
        equal(6000, trim(bounds), "fresh route has its own usable timestamps");
        bounds.observe(false, 9000);
        equal(44000, trim(bounds), "out-of-order processed timestamps fail closed");

        long[][] invalid = {{-1, 5000}, {0, 0}, {0, -1}, {0, 10001},
                {9000, 2000}, {Long.MAX_VALUE, 1}, {1, Long.MAX_VALUE}, {0, 288001}};
        for (long[] segment : invalid) {
            bounds.reset();
            bounds.observe(false, 10000);
            bounds.completed(segment[0], segment[1]);
            equal(44000, trim(bounds), "invalid segment uses old audio bounds");
            bounds.completed(0, 5000);
            equal(44000, trim(bounds), "invalid bounds disable trimming until reset");
        }
        bounds.reset();
        bounds.observe(false, 20000);
        bounds.completed(1000, 5000);
        bounds.completed(9000, 6000);
        equal(15000, trim(bounds), "several completed segments retain latest end");
        bounds.completed(1000, 5000);
        equal(44000, trim(bounds), "out-of-order segment queue fails closed");
        bounds.reset();
        bounds.observe(false, 288001);
        bounds.completed(0, 5000);
        equal(44000, trim(bounds), "unbounded audio timeline disables optimization");
        System.out.println("PASS completed-speech bounds; checks=" + checks);
    }
}
