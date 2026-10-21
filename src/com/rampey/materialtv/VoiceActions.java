package com.rampey.materialtv;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** TV context and command execution for an assistant session, independent of Home's visible UI.
 *  Refresh context before each request and before taking audio focus. Sequential requests may reuse this helper;
 *  the caller must discard stale plans before run, which executes on the main thread. */
final class VoiceActions {
    private final Context app;
    private final PackageManager pm;
    private final Consumer<Intent> launch;
    private final Set<String> packages = new HashSet<>();
    private final Set<String> inputs = new HashSet<>();
    private MediaController media;
    private boolean wasPlaying;

    /** The caller controls overlay dismissal and activity launching. Media and volume never launch Home. */
    VoiceActions(Context context, Consumer<Intent> launch) {
        app = context.getApplicationContext();
        pm = app.getPackageManager();
        this.launch = launch;
    }

    /** Snapshot before assistant audio focus pauses the current app; actions keep this request's media target. */
    JSONObject context(JSONArray recentAsks) {
        JSONObject out = new JSONObject();
        put(out, "time", new SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(new Date()));
        packages.clear();
        inputs.clear();
        JSONArray apps = new JSONArray();
        try {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                // Home is its own explicit action, not an app to open behind the assistant.
                if (app.getPackageName().equals(pkg) || !packages.add(pkg)) continue;
                JSONObject item = new JSONObject();
                put(item, "name", String.valueOf(info.loadLabel(pm)));
                put(item, "package", pkg);
                apps.put(item);
            }
        } catch (RuntimeException ignored) { }
        put(out, "apps", apps);
        put(out, "most_used", mostUsed());
        put(out, "inputs", inputContext());

        String foreground = foregroundApp();
        if (!foreground.isEmpty()) put(out, "foreground_app", foreground);
        media = currentSession(foreground);
        wasPlaying = false;
        if (media != null) try {
            PlaybackState state = media.getPlaybackState();
            wasPlaying = active(state);
            JSONObject now = new JSONObject();
            put(now, "app", media.getPackageName());
            put(now, "playing", wasPlaying);
            MediaMetadata metadata = media.getMetadata();
            if (metadata != null) {
                put(now, "title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                put(now, "artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            }
            put(out, "now_playing", now);
        } catch (RuntimeException ignored) { }

        AudioManager audio = app.getSystemService(AudioManager.class);
        if (audio != null) try {
            put(out, "volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                    + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (RuntimeException ignored) { }
        try {
            put(out, "recent_asks", recentAsks == null ? new JSONArray() : new JSONArray(recentAsks.toString()));
        } catch (JSONException ignored) {
            put(out, "recent_asks", new JSONArray());
        }
        return out;
    }

    private String mostUsed() {
        List<String> top = new ArrayList<>();
        try {
            UsageStatsManager usage = app.getSystemService(UsageStatsManager.class);
            if (usage == null) return "";
            long now = System.currentTimeMillis();
            List<UsageStats> used = new ArrayList<>(usage.queryAndAggregateUsageStats(now - 14L * 86_400_000L, now).values());
            used.sort((a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
            for (UsageStats item : used) {
                if (top.size() >= 6 || item.getTotalTimeInForeground() < 60_000) break;
                if (packages.contains(item.getPackageName())) top.add(item.getPackageName());
            }
        } catch (RuntimeException ignored) { }
        return String.join(",", top);
    }

    private JSONArray inputContext() {
        JSONArray out = new JSONArray();
        TvInputManager manager = app.getSystemService(TvInputManager.class);
        if (manager == null) return out;
        try {
            List<TvInputInfo> all = manager.getTvInputList();
            Set<String> allIds = new HashSet<>();
            for (TvInputInfo input : all) allIds.add(input.getId());
            Set<String> named = new HashSet<>();
            for (TvInputInfo input : all) {
                String parent = input.getParentId();
                if (input.getType() != TvInputInfo.TYPE_HDMI || parent == null || !allIds.contains(parent)) continue;
                named.add(parent);
                inputs.add(parent);
                JSONObject item = new JSONObject();
                put(item, "input_id", parent);
                put(item, "name", String.valueOf(input.loadLabel(app)));
                put(item, "on", manager.getInputState(input.getId()) == TvInputManager.INPUT_STATE_CONNECTED);
                out.put(item);
            }
            for (TvInputInfo input : all) {
                if (input.getType() != TvInputInfo.TYPE_HDMI || input.getParentId() != null || named.contains(input.getId())) continue;
                inputs.add(input.getId());
                JSONObject item = new JSONObject();
                put(item, "input_id", input.getId());
                put(item, "name", String.valueOf(input.loadLabel(app)));
                out.put(item);
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Only package-level activity events, never view or screen contents. Empty means unavailable. */
    private String foregroundApp() {
        String latest = "";
        long latestAt = -1;
        try {
            UsageStatsManager usage = app.getSystemService(UsageStatsManager.class);
            if (usage == null) return "";
            long now = System.currentTimeMillis();
            UsageEvents events = usage.queryEvents(now - 24L * 3_600_000L, now);
            if (events == null) return "";
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) break;
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                        && event.getTimeStamp() >= latestAt && event.getPackageName() != null) {
                    latest = event.getPackageName();
                    latestAt = event.getTimeStamp();
                }
            }
        } catch (RuntimeException ignored) { }
        return latest;
    }

    private MediaController currentSession(String foreground) {
        MediaSessionManager manager = app.getSystemService(MediaSessionManager.class);
        if (manager == null) return null;
        MediaController best = null;
        int bestScore = -1;
        try {
            for (MediaController candidate : manager.getActiveSessions(new ComponentName(app, MediaListener.class))) {
                PlaybackState state = candidate.getPlaybackState();
                int score = active(state) ? 3 : state != null && state.getState() == PlaybackState.STATE_PAUSED ? 2 : 1;
                // Prefer the app the person is using, even if another app left a stale PLAYING session behind.
                if (!foreground.isEmpty() && foreground.equals(candidate.getPackageName())) score += 10;
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        } catch (RuntimeException ignored) { }
        return best;
    }

    private static boolean active(PlaybackState state) {
        return state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
    }

    /** Run the plan against the captured context. A non-null result replaces the model's claimed success. */
    String run(JSONArray actions) {
        String note = null;
        for (int i = 0; actions != null && i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;
            try {
                String failure = runOne(action);
                if (failure != null) note = failure;
            } catch (RuntimeException unavailable) {
                note = "Couldn't complete that TV action";
            }
        }
        return note;
    }

    private String runOne(JSONObject action) {
        switch (action.optString("type")) {
            case "open": {
                String pkg = action.optString("package"), uri = action.optString("uri");
                if (!packages.contains(pkg) || app.getPackageName().equals(pkg)) return "Couldn't find that app";
                Intent intent;
                if (!uri.isEmpty()) intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
                else {
                    intent = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
                }
                if (intent == null) return "Couldn't open that app";
                start(intent);
                return null;
            }
            case "input": {
                String id = action.optString("input_id");
                TvInputManager manager = app.getSystemService(TvInputManager.class);
                if (!inputs.contains(id) || manager == null || manager.getTvInputInfo(id) == null) return "Couldn't find that input";
                start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)));
                return null;
            }
            case "media": {
                if (media == null) return "No media session is available";
                MediaController.TransportControls controls = media.getTransportControls();
                switch (action.optString("command")) {
                    case "play": controls.play(); wasPlaying = true; break;
                    case "pause": controls.pause(); wasPlaying = false; break;
                    case "next": controls.skipToNext(); break;
                    case "previous": controls.skipToPrevious(); break;
                    case "toggle":
                        // Speech capture may have temporarily paused the app; use its state before audio focus.
                        if (wasPlaying) controls.pause(); else controls.play();
                        wasPlaying = !wasPlaying;
                        break;
                    default: return "Didn't recognize that playback command";
                }
                return null;
            }
            case "volume": {
                AudioManager audio = app.getSystemService(AudioManager.class);
                if (audio == null) return "Volume controls aren't available";
                if (audio.isVolumeFixed()) return "This TV doesn't let apps change the volume";
                int stream = AudioManager.STREAM_MUSIC, flags = AudioManager.FLAG_SHOW_UI;
                String direction = action.optString("direction");
                if (direction.equals("mute")) audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags);
                else if (direction.equals("unmute")) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags);
                else if (direction.equals("up") || direction.equals("down")) {
                    int adjust = direction.equals("down") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE;
                    for (int i = 0; i < Math.max(1, Math.min(10, action.optInt("steps", 1))); i++)
                        audio.adjustStreamVolume(stream, adjust, flags);
                } else return "Didn't recognize that volume command";
                return null;
            }
            case "settings": {
                String name = action.optString("action");
                if (!name.startsWith("android.settings.")) return "Couldn't find that settings screen";
                start(new Intent(name));
                return null;
            }
            case "home":
                start(new Intent(Intent.ACTION_MAIN).setClass(app, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                return null;
            default: return "Didn't recognize that TV action";
        }
    }

    private void start(Intent intent) {
        // Keep HomeKeyService from treating a stock-home flash during app startup as a press of Home.
        if (intent.getComponent() == null || !app.getPackageName().equals(intent.getComponent().getPackageName()))
            HomeActivity.lastLaunchAt = SystemClock.elapsedRealtime();
        launch.accept(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void put(JSONObject object, String name, Object value) {
        try { object.put(name, value); } catch (JSONException ignored) { }
    }
}
