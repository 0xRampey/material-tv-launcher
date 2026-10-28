package com.rampey.voice;

/** The input to {@link Understanding}: what the person said, as text, or as audio when nothing local could hear it. */
public final class Utterance {
    /** The words, or null when only audio is available. */
    public final String text;
    /** A 16 kHz mono WAV, or null when there is text. */
    public final byte[] audio;

    private Utterance(String text, byte[] audio) {
        this.text = text;
        this.audio = audio;
    }

    public static Utterance text(String text) {
        return new Utterance(text, null);
    }

    public static Utterance audio(byte[] wav) {
        return new Utterance(null, wav);
    }
}
