package com.rampey.materialtv;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Required by Android for an assistant app. Ask captures audio directly and sends it to Gemini, not this service. */
public class AssistRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try {
            callback.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (Exception ignored) {
        }
    }

    @Override protected void onCancel(Callback callback) {
    }

    @Override protected void onStopListening(Callback callback) {
    }
}
