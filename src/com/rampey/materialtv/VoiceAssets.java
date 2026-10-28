package com.rampey.materialtv;

import android.content.Context;
import com.rampey.voice.Hear;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Supplies the existing speech detector; no speech recognizer or language model is loaded. */
final class VoiceAssets {
    private VoiceAssets() { }

    static Hear capture(Context context) {
        File dir = new File(context.getFilesDir(), "voice-capture");
        File detector = new File(dir, "silero_vad.onnx");
        if (!detector.isFile()) {
            File temporary = new File(dir, "silero_vad.onnx.tmp");
            try {
                Files.createDirectories(dir.toPath());
                try (InputStream input = context.getAssets().open("voice/silero_vad.onnx")) {
                    Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary.toPath(), detector.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) {
                temporary.delete();
                // Engine already supports loudness-based endpoint detection when VAD is unavailable.
                android.util.Log.w("MaterialTvVoice", "Speech detector unavailable; using capture fallback");
            }
        }
        return Hear.auto(dir);
    }
}
