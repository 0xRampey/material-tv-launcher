package com.rampey.voice;

/** What one listen heard. */
public final class Heard {
    /** The transcript; "" when speech was heard but couldn't be made out, or no model was loaded yet. */
    public final String text;
    /** Which mic heard it. */
    public final Voice.Mic mic;
    /** How long the speech itself was (between the first and last loud frame). */
    public final long speechMs;
    /** How long the mic was open, denoising took (-1: skipped) and transcription took (-1: streaming or skipped). */
    public final long recordMs, denoiseMs, transcribeMs;
    /** Monotonic start (mic press when adopted, otherwise listen) and completed-hearing times. */
    public final long startedElapsedMs, finishedElapsedMs;
    /** Time spent in streaming decode calls, including the final drain; -1 when streaming ASR was skipped. */
    public final long streamingDecodeMs;
    /** Wait after recording stops for streaming recognition to finish; -1 when streaming ASR was skipped. */
    public final long recognitionDrainMs;
    /** Largest queued audio duration and single decode burst; -1 when streaming ASR was skipped. */
    public final long recognitionQueueMs, recognitionMaxDecodeMs;
    /** Largest gap between capture reads, in milliseconds. */
    public final long captureReadGapMs;
    /** A local recognizer was ready for this listen. False for explicit capture-only listens. */
    public final boolean recognizerLoaded;
    /** The whole listen as a 16 kHz mono 16-bit WAV. In memory only; nothing is written anywhere. */
    public final byte[] wav;
    /** One line of measurements for logs: mic, restarts, floor/peak dB, model, timings, boost, VAD use. */
    public final String stats;

    Heard(String text, Voice.Mic mic, long speechMs, long recordMs, long denoiseMs, long transcribeMs,
          boolean recognizerLoaded, byte[] wav, String stats, long startedElapsedMs, long finishedElapsedMs,
          long streamingDecodeMs, long recognitionDrainMs, long recognitionQueueMs,
          long recognitionMaxDecodeMs, long captureReadGapMs) {
        this.text = text;
        this.mic = mic;
        this.speechMs = speechMs;
        this.recordMs = recordMs;
        this.denoiseMs = denoiseMs;
        this.transcribeMs = transcribeMs;
        this.recognizerLoaded = recognizerLoaded;
        this.wav = wav;
        this.stats = stats;
        this.startedElapsedMs = startedElapsedMs;
        this.finishedElapsedMs = finishedElapsedMs;
        this.streamingDecodeMs = streamingDecodeMs;
        this.recognitionDrainMs = recognitionDrainMs;
        this.recognitionQueueMs = recognitionQueueMs;
        this.recognitionMaxDecodeMs = recognitionMaxDecodeMs;
        this.captureReadGapMs = captureReadGapMs;
    }
}
