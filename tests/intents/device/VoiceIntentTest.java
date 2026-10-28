package com.rampey.materialtv;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Developer-only tests: production command executor; optional synthetic text planner and actual intent dispatch. */
public final class VoiceIntentTest extends Instrumentation {
    private Bundle args;
    private int checks;
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    @Override public void onCreate(Bundle args) { this.args = args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String query = "Can’t Stop / Don't Stop & Me + You — Live 2026";
            List<Intent> youtube = MediaIntents.candidates(MediaIntents.YOUTUBE, query, true);
            check(youtube.size() == 4, "YouTube candidates must include play/search paths");
            for (Intent candidate : youtube) {
                check(MediaIntents.YOUTUBE.equals(candidate.getPackage()), "All candidates target the requested app");
                if (candidate.hasExtra(SearchManager.QUERY)) check(query.equals(candidate.getStringExtra(SearchManager.QUERY)), "Query must be unchanged");
                if (candidate.getData() != null) check(query.equals(candidate.getData().getQueryParameter("search_query")), "URL query must round trip without truncation");
            }
            check(MediaIntents.requestsPlayback(youtube.get(0)), "Playback attempted first");
            check(!MediaIntents.requestsPlayback(youtube.get(2)), "Search is not playback");
            check(MediaIntents.candidates(MediaIntents.YOUTUBE, "  ", true).isEmpty(), "No blank content request");
            check(MediaIntents.candidates(MediaIntents.YOUTUBE, "x".repeat(1001), true).isEmpty(), "Bounded query");
            for (Intent candidate : MediaIntents.candidates(MediaIntents.SPOTIFY, query, false))
                if (candidate.getData() != null) check(candidate.getData().toString().equals("spotify:search:" + Uri.encode(query)), "Spotify encoding preserves punctuation");
            check(!MediaIntents.safeLink(MediaIntents.YOUTUBE, Uri.parse("intent://evil")), "No arbitrary intent URL");
            check(!MediaIntents.safeLink(MediaIntents.YOUTUBE, Uri.parse("https://youtube.com.example.org/watch?v=bad")), "No spoof YouTube host");
            check(MediaIntents.safeLink(MediaIntents.YOUTUBE, Uri.parse("https://www.youtube.com/watch?v=real")), "Supplied YouTube link supported");
            check(!MediaIntents.safeLink("org.example.app", Uri.parse("file:///sdcard/private")), "No local file links");
            check(!AssistantSetup.validKey("short"), "Short key rejected");
            check(!AssistantSetup.validKey("long enough but spaced"), "Whitespace in key rejected");
            check(AssistantSetup.validKey("test-key-never-sent-to-network"), "Single token accepted");
            File keyTest = new File(getTargetContext().getCacheDir(), "key-storage-test");
            try {
                AssistantSetup.storeKey(keyTest, "test-key-never-sent-to-network");
                try { AssistantSetup.storeKey(keyTest, "bad key"); throw new AssertionError("Invalid replacement accepted"); }
                catch (IllegalArgumentException expected) { }
                check(new String(java.nio.file.Files.readAllBytes(keyTest.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                        .equals("test-key-never-sent-to-network"), "Invalid replacement preserves saved key");
            } finally { keyTest.delete(); }
            if (args.getString("verify_key", "").equals("true")) {
                File realKey = new File(getTargetContext().getExternalFilesDir(null), "openrouter_key");
                byte[] priorKey = java.nio.file.Files.readAllBytes(realKey.toPath());
                String priorAssistant = android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(), "voice_interaction_service");
                AssistantSetup.saveKey(getTargetContext(), new String(priorKey, java.nio.charset.StandardCharsets.UTF_8));
                check(new String(priorKey, java.nio.charset.StandardCharsets.UTF_8).trim().equals(
                        new String(java.nio.file.Files.readAllBytes(realKey.toPath()), java.nio.charset.StandardCharsets.UTF_8)), "In-app key save persists the supplied key");
                check(java.util.Objects.equals(priorAssistant, android.provider.Settings.Secure.getString(
                        getTargetContext().getContentResolver(), "voice_interaction_service")), "Saving key must keep current assistant");
            }
            if (args.getString("resume_card", "").equals("true")) {
                Activity home = startActivitySync(new Intent(getTargetContext(), HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                getTargetContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
                        .setPackage(MediaIntents.YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                MediaController before = awaitPlaying(MediaIntents.YOUTUBE, null, 25000);
                check(before != null, "Fixture video must actually play");
                String title = before.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE);
                check(title != null && !title.isEmpty(), "Playback title must be exposed");
                check(MediaResume.matches(MediaIntents.YOUTUBE, title, before), "Same item matches");
                check(!MediaResume.matches(MediaIntents.YOUTUBE, "different title", before), "Different item rejected");
                check(!MediaResume.matches(MediaIntents.SPOTIFY, title, before), "Different app rejected");
                before.getTransportControls().seekTo(30000);
                long seekDeadline = SystemClock.elapsedRealtime() + 10000;
                while (before.getPlaybackState().getPosition() < 28000 && SystemClock.elapsedRealtime() < seekDeadline) SystemClock.sleep(200);
                check(before.getPlaybackState().getPosition() >= 28000, "Fixture must seek far enough to distinguish resume from restart");
                long oldPosition = before.getPlaybackState().getPosition();
                getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                SystemClock.sleep(2500);
                View[] play = {null};
                runOnMainSync(() -> { play[0] = findPlay(home.getWindow().getDecorView()); });
                check(play[0] != null && play[0].isShown(), "Now Playing control is visible");
                java.lang.reflect.Field remembered = HomeActivity.class.getDeclaredField("showingRemembered");
                remembered.setAccessible(true);
                check(remembered.getBoolean(home), "Test must use remembered card after YouTube releases its session");
                runOnMainSync(() -> play[0].performClick());
                MediaController after = awaitPlaying(MediaIntents.YOUTUBE, title, 12000);
                check(after != null, "Card resumes the same title in YouTube");
                long resumeDeadline = SystemClock.elapsedRealtime() + 8000;
                while (after.getPlaybackState().getPosition() + 2000 < oldPosition && SystemClock.elapsedRealtime() < resumeDeadline) SystemClock.sleep(200);
                long newPosition = after.getPlaybackState().getPosition();
                result.putString("resume_title", title);
                result.putLong("position_before", oldPosition);
                result.putLong("position_after", newPosition);
                check(newPosition + 2000 >= oldPosition, "Card preserves playback position");
            }
            List<Intent> launches = new ArrayList<>();
            VoiceActions[] executor = {null}; JSONObject[] context = {null};
            runOnMainSync(() -> {
                executor[0] = new VoiceActions(getTargetContext(), intent -> launches.add(new Intent(intent)));
                context[0] = executor[0].context(new JSONArray());
            });
            String[] note = {null};
            runOnMainSync(() -> {
                try {
                    note[0] = executor[0].run(new JSONArray().put(new JSONObject().put("type", "open").put("package", MediaIntents.YOUTUBE))
                            .put(new JSONObject().put("type", "android_use").put("package", MediaIntents.YOUTUBE)));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            check(note[0] != null && launches.isEmpty(), "Old navigation plan rejected before any launch");
            runOnMainSync(() -> {
                try {
                    note[0] = executor[0].run(new JSONArray().put(new JSONObject().put("type", "content").put("package", MediaIntents.YOUTUBE)
                            .put("query", query).put("mode", "play")).put(new JSONObject().put("type", "media").put("command", "play")));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            check(note[0] != null && launches.isEmpty(), "No old-session play after a new content request");
            String request = args.getString("request", "");
            boolean audio = args.getString("audio_fixture", "").equals("true");
            if (!request.isEmpty() || audio) {
                VoiceAgent agent = new VoiceAgent(new File(getTargetContext().getExternalFilesDir(null), "openrouter_key"));
                long started = SystemClock.elapsedRealtime();
                VoiceAgent.Result plan = audio ? agent.askAudio(java.nio.file.Files.readAllBytes(new File(
                        getTargetContext().getExternalFilesDir(null), "release-test.wav").toPath()), context[0]) : agent.ask(request, context[0]);
                check(plan.error == null, "Planner failed: " + plan.error);
                if (audio) check(!plan.heard.isEmpty(), "Audio must produce recognized speech");
                JSONArray planActions = plan.actions;
                String expectedPackage = args.getString("expected_package", "");
                if (!expectedPackage.isEmpty()) {
                    check(planActions.length() == 1, "Expected one intent action, got " + planActions);
                    JSONObject first = planActions.getJSONObject(0);
                    check((audio ? "open" : "content").equals(first.optString("type")), "Unexpected action: " + first);
                    check(expectedPackage.equals(first.optString("package")), "Wrong app: " + first);
                    String expectedQuery = args.getString("expected_query", "");
                    if (!expectedQuery.isEmpty()) check(expectedQuery.equals(first.optString("query")), "Changed title: " + first);
                }
                if (args.getString("expect_no_actions", "").equals("true")) check(planActions.length() == 0, "Unsupported request should not dispatch " + planActions);
                runOnMainSync(() -> note[0] = executor[0].run(planActions));
                if (!expectedPackage.isEmpty()) check(executor[0].lastError() == null, "Content dispatch failed: " + executor[0].lastError());
                JSONObject evidence = new JSONObject().put("request", request).put("plan", plan.actions).put("say", plan.say)
                        .put("note", note[0]).put("planner_ms", plan.millis).put("cost", plan.cost);
                if (!launches.isEmpty()) {
                    Intent intent = launches.get(0);
                    evidence.put("intent_action", intent.getAction()).put("intent_package", intent.getPackage())
                            .put("intent_data", intent.getDataString()).put("intent_query", intent.getStringExtra(SearchManager.QUERY));
                    if (args.getString("execute", "").equals("true")) {
                        runOnMainSync(() -> getTargetContext().startActivity(intent));
                        evidence.put("dispatched", true);
                    }
                }
                evidence.put("text_to_dispatch_ms", SystemClock.elapsedRealtime() - started);
                result.putString("evidence", evidence.toString());
            }
            result.putInt("checks", checks);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("failure", error.toString());
            result.putInt("checks", checks);
            finish(0, result);
        }
    }
    private MediaController awaitPlaying(String pkg, String title, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        MediaSessionManager sessions = getTargetContext().getSystemService(MediaSessionManager.class);
        while (SystemClock.elapsedRealtime() < deadline) {
            for (MediaController controller : sessions.getActiveSessions(new ComponentName(getTargetContext(), MediaListener.class))) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING && pkg.equals(controller.getPackageName())
                        && controller.getMetadata() != null && controller.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE) != null
                        && !controller.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE).isEmpty()
                        && (title == null || MediaResume.matches(pkg, title, controller))) return controller;
            }
            SystemClock.sleep(200);
        }
        return null;
    }
    private View findPlay(View view) {
        if ("Play or pause".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findPlay(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
