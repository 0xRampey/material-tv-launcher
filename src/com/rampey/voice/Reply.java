package com.rampey.voice;

/** Stage 4 (Hear -> Understand -> Act -> Reply): tells the person what happened (speech, a toast...). Main thread. */
public interface Reply<P> {
    /** note: what {@link Act} returned (null if it had nothing to add, or there is no act stage). */
    void reply(P plan, String note);
}
