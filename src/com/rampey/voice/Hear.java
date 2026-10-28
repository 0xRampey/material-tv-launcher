package com.rampey.voice;

import java.io.File;

/**
 * The hearing stage: which on-device speech-to-text model to run, and the folder that holds it. The same folder may
 * also hold silero_vad.onnx (voice detection) and gtcrn_simple.onnx (denoise); both are optional. See README for the
 * files each model needs.
 */
public final class Hear {

    public enum Model {
        /** Whatever the folder holds, in this order: Parakeet, Whisper, Moonshine, NeMo CTC, zipformer. */
        AUTO,
        /** Streaming zipformer transducer (encoder.onnx, decoder.onnx, joiner.onnx): live words. */
        ZIPFORMER,
        /** Streaming NeMo CTC (model.onnx): live words. */
        NEMO,
        /** Moonshine (encoder_model.ort, decoder_model_merged.ort): whole clip. */
        MOONSHINE,
        /** Whisper, English (whisper-encoder.onnx, whisper-decoder.onnx): whole clip. */
        WHISPER,
        /** Parakeet / NeMo CTC whole-clip (parakeet.onnx): whole clip, punctuation included. */
        PARAKEET;

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    final File dir;
    final Model model;

    private Hear(File dir, Model model) {
        this.dir = dir;
        this.model = model;
    }

    /** Picks the model by the files present in dir. */
    public static Hear auto(File dir) {
        return new Hear(dir, Model.AUTO);
    }

    public static Hear nemo(File dir) {
        return new Hear(dir, Model.NEMO);
    }

    public static Hear zipformer(File dir) {
        return new Hear(dir, Model.ZIPFORMER);
    }

    public static Hear moonshine(File dir) {
        return new Hear(dir, Model.MOONSHINE);
    }

    public static Hear whisper(File dir) {
        return new Hear(dir, Model.WHISPER);
    }

    public static Hear parakeet(File dir) {
        return new Hear(dir, Model.PARAKEET);
    }

    public File dir() {
        return dir;
    }

    public Model model() {
        return model;
    }
}
