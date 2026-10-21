package com.rampey.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Starts listening at the mic press, before the app's listening screen (Ask, in the launcher) is up, and hands the
 * live recording to the next listen. Reached through {@link Voice#atPress} (Android TV only).
 *
 * The remote's audio reaches apps only through Google's remote service, and without Google Assistant installed it runs
 * the remote in its legacy mode: a short audio window per press, closed by the service itself. It routes that audio
 * only to a voice recording that starts after the remote's mic is live (an earlier one is ignored). Coming from inside
 * an app, Ask needs ~1.5-2 s to appear, which often misses the window. So the assistant session starts a recording at
 * once, restarts it every 0.4 s until the service sets up its route, then holds it (its buffer keeps ~4 s of what you
 * say) until Ask takes it over with {@link #take()}.
 */
final class MicKeeper {
    static final int RATE = 16000;
    private static final int BYTES = RATE * 2 * 4 * 4;   // ~4 s of stereo float: nothing said before Ask appears is lost
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static AudioRecord rec;
    private static long startedAt, openedAt;
    private static int epoch;   // one per press: a listen from an earlier press stops when it changes

    /** At the press: start a recording now and keep it until Ask takes it (or 6 s pass). */
    static synchronized void start(Context context) {
        app = context.getApplicationContext();
        release();
        epoch++;
        startedAt = SystemClock.elapsedRealtime();
        rec = open();
        MAIN.removeCallbacks(TICK);
        MAIN.postDelayed(TICK, 400);
        MAIN.removeCallbacks(EXPIRE);
        MAIN.postDelayed(EXPIRE, 6000);
    }

    /** Ask's turn: the live recording (caller owns it from now on), or null if none is waiting. */
    static synchronized AudioRecord take() {
        MAIN.removeCallbacks(TICK);
        MAIN.removeCallbacks(EXPIRE);
        AudioRecord r = rec;
        rec = null;
        return r;
    }

    /** When the press happened (elapsedRealtime), for continuing the restart window. */
    static synchronized long startedAt() {
        return startedAt;
    }

    /** When the recording Ask will take was opened (elapsedRealtime): its first sample, for a press-relative clock. */
    static synchronized long openedAt() {
        return openedAt;
    }

    static synchronized int epoch() {
        return epoch;
    }

    /** A mic press (which chimes) happened in the last few seconds. */
    static synchronized boolean pressedRecently() {
        return startedAt > 0 && SystemClock.elapsedRealtime() - startedAt < 8000;
    }

    private static final Runnable TICK = () -> {
        synchronized (MicKeeper.class) {
            if (rec == null) return;
            boolean up = routeUp(app);
            if (!up) {
                release();
                rec = open();
            }
            if (!up && SystemClock.elapsedRealtime() - startedAt < 3000) MAIN.postDelayed(MicKeeper.TICK, 400);
        }
    };

    private static final Runnable EXPIRE = () -> {
        synchronized (MicKeeper.class) {
            release();
        }
    };

    private static void release() {
        if (rec == null) return;
        try { rec.stop(); } catch (RuntimeException ignored) { }
        rec.release();
        rec = null;
    }

    /** Both TV mics as float when allowed, else mono 16-bit; null if the mic can't be opened. */
    private static AudioRecord open() {
        AudioRecord r = null;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT, BYTES);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); r = null; }
            if (r == null) {
                r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, BYTES / 4);
                if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
            }
            r.startRecording();
            openedAt = SystemClock.elapsedRealtime();
            return r;
        } catch (RuntimeException e) {
            if (r != null) r.release();
            return null;
        }
    }

    /** Google's remote service has set up its route for the remote's mic: a remote-submix device at an audio-policy
     *  address ("...:ap:NN..."), not the TV's always-present default one. */
    static boolean routeUp(Context context) {
        AudioManager am = context == null ? null : context.getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL))
            if (d.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX && d.getAddress().contains(":ap:")) return true;
        return false;
    }
}
