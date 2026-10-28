package com.rampey.voice;

/**
 * Stage 2 (Hear -> Understand -> Act -> Reply): what was said plus the app's context, turned into a plan. Nothing ships
 * for this stage; plug in a model call, a grammar, or a rules table. Called on a background thread; may block.
 *
 * @param <C> the app's context (what's on screen, what's playing, ...)
 * @param <P> the plan (intent, actions, a line to say)
 */
public interface Understand<C, P> {
    P understand(Utterance utterance, C context);
}
