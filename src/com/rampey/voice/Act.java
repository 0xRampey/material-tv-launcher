package com.rampey.voice;

/** Stage 3 (Hear -> Understand -> Act -> Reply): carries out a plan. Called on the main thread. Nothing ships for it. */
public interface Act<P> {
    /** Returns a note to show or say instead of the plan's own line when something couldn't be done, else null. */
    String act(P plan);
}
