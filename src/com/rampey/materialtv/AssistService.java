package com.rampey.materialtv;

import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

/** Registers the launcher as the TV assistant without requesting app screen contents. */
public class AssistService extends VoiceInteractionService {
    private static AssistService active;
    static boolean showAssistant() {
        if (active == null) return false;
        active.showSession(new android.os.Bundle(), 0);
        return true;
    }
    @Override public void onShutdown() {
        if (active == this) active = null;
        super.onShutdown();
    }
    @Override public void onReady() {
        super.onReady();
        active = this;
        getSharedPreferences("voice", MODE_PRIVATE).edit().putString("route", "cloud_audio").apply();
        android.util.Log.i("MaterialTvVoice", "Gemini preview ready route=cloud_audio capture_only=true");
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
}
