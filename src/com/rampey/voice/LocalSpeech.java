package com.rampey.voice;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;

/**
 * On-device speech to text: sherpa-onnx models running on the device's CPU. A streaming model (zipformer transducer or
 * NeMo CTC) gives words while you speak; Engine's separate detector ends capture. A whole-clip model (Moonshine,
 * Whisper, Parakeet) transcribes the finished clip. The models live in one folder; loaded once, kept while the process
 * runs.
 */
final class LocalSpeech {
    private static volatile OnlineRecognizer recognizer;
    static volatile long loadMs = -1;
    static volatile String error;
    /** The kind that loaded (null until one has). */
    static volatile Hear.Model loaded;

    private LocalSpeech() {
    }

    /** Which model the folder holds, in this order: Parakeet, Whisper, Moonshine, NeMo CTC, zipformer; null if none. */
    static Hear.Model detect(File dir) {
        if (parakeet(dir)) return Hear.Model.PARAKEET;
        if (whisper(dir)) return Hear.Model.WHISPER;
        if (moonshine(dir)) return Hear.Model.MOONSHINE;
        if (nemoCtc(dir)) return Hear.Model.NEMO;
        if (new File(dir, "encoder.onnx").exists()) return Hear.Model.ZIPFORMER;
        return null;
    }

    static Hear.Model resolve(File dir, Hear.Model m) {
        return m == Hear.Model.AUTO ? detect(dir) : m;
    }

    static boolean installed(File dir, Hear.Model m) {
        Hear.Model k = resolve(dir, m);
        if (k == null || !new File(dir, "tokens.txt").exists()) return false;
        switch (k) {
            case PARAKEET: return parakeet(dir);
            case WHISPER: return whisper(dir);
            case MOONSHINE: return moonshine(dir);
            case NEMO: return new File(dir, "model.onnx").exists();
            default: return new File(dir, "encoder.onnx").exists();
        }
    }

    /** A NeMo streaming CTC model (one file, model.onnx): live words, a simpler decoder than the transducer. */
    static boolean nemoCtc(File dir) {
        return new File(dir, "model.onnx").exists() && !new File(dir, "encoder.onnx").exists();
    }

    /** A whole-clip NeMo CTC model such as Parakeet 110M (parakeet.onnx): one pass over the clip, punctuation included. */
    static boolean parakeet(File dir) {
        return new File(dir, "parakeet.onnx").exists();
    }

    /** A Whisper model (sherpa-onnx export: whisper-encoder.onnx + whisper-decoder.onnx): whole-clip, English only. */
    static boolean whisper(File dir) {
        return new File(dir, "whisper-encoder.onnx").exists() && new File(dir, "whisper-decoder.onnx").exists();
    }

    /** A Moonshine model (encoder + merged decoder): transcribes a finished clip, no live words. */
    static boolean moonshine(File dir) {
        return new File(dir, "encoder_model.ort").exists() && new File(dir, "decoder_model_merged.ort").exists();
    }

    private static volatile OfflineRecognizer offline;
    private static volatile com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser denoiser;
    private static volatile boolean extrasLoaded;

    /** A fresh Silero voice detector for one listen (it keeps state), or null if the model isn't there. */
    static com.k2fsa.sherpa.onnx.Vad newVad(File dir) {
        File m = new File(dir, "silero_vad.onnx");
        if (!m.exists()) return null;
        try {
            return new com.k2fsa.sherpa.onnx.Vad(com.k2fsa.sherpa.onnx.VadModelConfig.builder()
                    .setSileroVadModelConfig(com.k2fsa.sherpa.onnx.SileroVadModelConfig.builder()
                            .setModel(m.getPath())
                            .setThreshold(0.35f)            // soft voices from across the room
                            .setMinSilenceDuration(0.6f)    // a pause shorter than this is still "talking"
                            .setMinSpeechDuration(0.15f)
                            .setWindowSize(512)
                            .setMaxSpeechDuration(15f)
                            .build())
                    .setSampleRate(16000)
                    .setNumThreads(1)
                    .build());
        } catch (Throwable e) {
            android.util.Log.w(Voice.tag, "vad unavailable: " + e);
            return null;
        }
    }

    /** GTCRN speech enhancement: noise removed, voice cleaned, before transcription. Loads once. */
    static float[] denoise(float[] samples, int rate, File dir) {
        if (!extrasLoaded) {
            synchronized (LocalSpeech.class) {
                if (!extrasLoaded) {
                    File m = new File(dir, "gtcrn_simple.onnx");
                    if (m.exists()) {
                        try {
                            denoiser = new com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser(
                                    com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig.builder()
                                            .setModel(com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig.builder()
                                                    .setGtcrn(com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig.builder()
                                                            .setModel(m.getPath()).build())
                                                    .setNumThreads(2).build())
                                            .build());
                        } catch (Throwable e) {
                            android.util.Log.w(Voice.tag, "denoiser unavailable: " + e);
                        }
                    }
                    extrasLoaded = true;
                }
            }
        }
        com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser d = denoiser;
        if (d == null) return null;
        com.k2fsa.sherpa.onnx.DenoisedAudio out = d.run(samples, rate);
        return out == null ? null : out.getSamples();
    }

    static OfflineRecognizer peekOffline() {
        return offline;
    }

    /** Whole-clip transcription; errors propagate to Engine. Call off the main thread. */
    static synchronized String transcribe(float[] samples, int rate, java.util.function.BooleanSupplier active) {
        if (!active.getAsBoolean()) return "";
        OfflineRecognizer r = offline;
        if (r == null || samples.length == 0) return "";
        OfflineStream st = r.createStream();
        try {
            st.acceptWaveform(samples, rate);
            AsrDiagnostics.Span diagnostic = AsrDiagnostics.begin();
            boolean decoded = false;
            try {
                r.decode(st);
                decoded = true;
            } finally {
                AsrDiagnostics.end(diagnostic, decoded);
            }
            return r.getResult(st).getText().trim();
        } finally {
            st.release();
        }
    }

    /** The recognizer if it's already loaded, else null (never waits for a load in progress). */
    static OnlineRecognizer peek() {
        return recognizer;
    }

    /** Frees the loaded models; the next {@link #get} loads again. Not while a listen is running. */
    static synchronized void unload() {
        if (recognizer != null) recognizer.release();
        if (offline != null) offline.release();
        if (denoiser != null) denoiser.release();
        recognizer = null;
        offline = null;
        denoiser = null;
        extrasLoaded = false;
        loaded = null;
        error = null;
        loadMs = -1;
    }

    /** Blocking; call off the main thread. Null if the model or the native library can't be used (or it's whole-clip). */
    static synchronized OnlineRecognizer get(File dir, Hear.Model want) {
        if (recognizer != null || offline != null || error != null || !installed(dir, want)) return recognizer;
        Hear.Model kind = resolve(dir, want);
        long t = System.currentTimeMillis();
        if (kind == Hear.Model.MOONSHINE || kind == Hear.Model.WHISPER || kind == Hear.Model.PARAKEET) {
            try {
                OfflineModelConfig.Builder b = OfflineModelConfig.builder();
                if (kind == Hear.Model.PARAKEET) {
                    b.setNemo(com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig.builder()
                            .setModel(new File(dir, "parakeet.onnx").getPath()).build());
                } else if (kind == Hear.Model.WHISPER) {
                    b.setWhisper(com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig.builder()
                            .setEncoder(new File(dir, "whisper-encoder.onnx").getPath())
                            .setDecoder(new File(dir, "whisper-decoder.onnx").getPath())
                            .setLanguage("en")
                            .setTask("transcribe")
                            .setTailPaddings(300)   // 3 s of padding instead of the default 10 s: whisper pads every clip (slow on this chip)
                            .build());
                } else {
                    b.setMoonshine(OfflineMoonshineModelConfig.builder()
                            .setEncoder(new File(dir, "encoder_model.ort").getPath())
                            .setMergedDecoder(new File(dir, "decoder_model_merged.ort").getPath())
                            .build());
                }
                OfflineModelConfig model = b
                        .setTokens(new File(dir, "tokens.txt").getPath())
                        .setNumThreads(4)
                        .setProvider("cpu")
                        .setDebug(false)
                        .build();
                offline = new OfflineRecognizer(OfflineRecognizerConfig.builder()
                        .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                        .setOfflineModelConfig(model)
                        .setDecodingMethod("greedy_search")
                        .build());
                loaded = kind;
                loadMs = System.currentTimeMillis() - t;
                android.util.Log.i(Voice.tag, "local speech (" + kind.id() + ") loaded in " + loadMs + " ms");
            } catch (Throwable e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                android.util.Log.w(Voice.tag, "local speech unavailable: " + error);
            }
            return null;
        }
        try {
            OnlineModelConfig.Builder mb = OnlineModelConfig.builder();
            if (kind == Hear.Model.NEMO) {
                mb.setNeMoCtc(com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig.builder()
                        .setModel(new File(dir, "model.onnx").getPath()).build());
            } else {
                mb.setTransducer(OnlineTransducerModelConfig.builder()
                        .setEncoder(new File(dir, "encoder.onnx").getPath())
                        .setDecoder(new File(dir, "decoder.onnx").getPath())
                        .setJoiner(new File(dir, "joiner.onnx").getPath())
                        .build());
            }
            OnlineModelConfig model = mb
                    .setTokens(new File(dir, "tokens.txt").getPath())
                    .setNumThreads(4)
                    .setProvider("cpu")
                    .setDebug(false)
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    // nothing said yet: give up after 6 s of silence (our own first-words timeout also applies)
                    .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(6f).build())
                    // after speech: done at 1.3 s of silence (people pause mid-sentence; 0.8 s cut them off)
                    .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(1.3f).build())
                    // never longer than 12 s
                    .setRule3(EndpointRule.builder().setMustContainNonSilence(false).setMinUtteranceLength(12f).build())
                    .build();
            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                    .setOnlineModelConfig(model)
                    .setEndpointConfig(endpoint)
                    .setEnableEndpoint(true)
                    .setDecodingMethod("greedy_search")
                    .build();
            recognizer = new OnlineRecognizer(config);
            loaded = kind;
            loadMs = System.currentTimeMillis() - t;
            android.util.Log.i(Voice.tag, "local speech (" + (kind == Hear.Model.NEMO ? "nemo-ctc" : "transducer") + ") loaded in " + loadMs + " ms");
        } catch (Throwable e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            android.util.Log.w(Voice.tag, "local speech unavailable: " + error);
        }
        return recognizer;
    }
}
