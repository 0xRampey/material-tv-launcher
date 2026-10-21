package com.rampey.voice;

/**
 * Turn-taking: when a listen ends. Today that is silence after speech; a learned end-of-turn model (Smart Turn style)
 * can slot in here later.
 *
 * quietMs is the pause that ends a turn when Silero isn't available (loudness only).
 * With Silero, capture ends independently of the recognizer using the tuned waits:
 * 200 ms after speech on the remote's mic, 1000 ms on the TV's (Silero itself already waits 0.6 s).
 */
public final class Turns {
    final long quietMs, firstWordsMs, deadlineMs;

    private Turns(long quietMs, long firstWordsMs, long deadlineMs) {
        this.quietMs = quietMs;
        this.firstWordsMs = firstWordsMs;
        this.deadlineMs = deadlineMs;
    }

    /** The tuned defaults: 1300 ms of quiet (people pause mid-sentence), 6 s to start talking, 15 s at most. */
    public static Turns defaults() {
        return silence(1300);
    }

    /** Ends the turn after quietMs of silence following speech. */
    public static Turns silence(long quietMs) {
        return new Turns(quietMs, 6000, 15000);
    }

    /** Give up when no one starts talking within ms (default 6000). */
    public Turns firstWords(long ms) {
        return new Turns(quietMs, ms, deadlineMs);
    }

    /** Never listen longer than ms (default 15000). */
    public Turns deadline(long ms) {
        return new Turns(quietMs, firstWordsMs, ms);
    }
}
