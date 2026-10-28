package com.rampey.materialtv;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** The remote mic listens in the system assistant window, over the app already on screen. */
public class AssistSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        final long started = android.os.SystemClock.elapsedRealtime();
        MaterialVoiceSession session = new MaterialVoiceSession(this);
        android.util.Log.i("MaterialTvVoice", "ASSIST_STARTUP stage=construct elapsed_ms="
                + (android.os.SystemClock.elapsedRealtime() - started));
        return session;
    }
}
