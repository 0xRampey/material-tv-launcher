package com.rampey.materialtv;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

/** A bounded resume request for the remembered item, never a global Play key or a title search. */
final class MediaResume implements Runnable {
    private final Context context;
    private final Handler handler;
    private final String pkg, title;
    private final long deadline, position;
    private boolean sent, sought, cancelled;
    MediaResume(Context context, Handler handler, String pkg, CharSequence title, long position) {
        this.context = context.getApplicationContext(); this.handler = handler; this.pkg = pkg;
        this.title = title == null ? "" : title.toString();
        this.position = Math.max(0, position);
        deadline = SystemClock.elapsedRealtime() + 8000;
    }
    void start() { handler.postDelayed(this, 200); }
    void cancel() { cancelled = true; handler.removeCallbacks(this); }
    static boolean matches(String pkg, String title, MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        return pkg.equals(controller.getPackageName()) && metadata != null && !title.isEmpty()
                && title.equals(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
    }
    @Override public void run() {
        if (cancelled || SystemClock.elapsedRealtime() >= deadline) return;
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            if (manager != null) for (MediaController controller : manager.getActiveSessions(new ComponentName(context, MediaListener.class))) {
                if (!matches(pkg, title, controller)) continue;
                PlaybackState state = controller.getPlaybackState();
                if (state == null) continue;
                long duration = controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
                boolean restorePosition = position > 2000 && duration > position
                        && state.getPosition() + 2000 < position && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
                if (restorePosition && !sought) { controller.getTransportControls().seekTo(position); sought = true; }
                if (state.getState() == PlaybackState.STATE_PLAYING && !restorePosition) return;
                if (!sent && (state.getActions() & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0) {
                    controller.getTransportControls().play(); sent = true;
                }
            }
        } catch (SecurityException unavailable) { return; }
        handler.postDelayed(this, 200);
    }
}
