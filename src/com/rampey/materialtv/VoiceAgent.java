package com.rampey.materialtv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Gemini interprets recorded speech and TV context through OpenRouter. The user supplies the key; it is never bundled.
 * Runs on a background thread; the caller posts the result to the main thread.
 */
final class VoiceAgent {
    static final String MODEL = "google/gemini-3.5-flash-lite";          // typed requests
    static final String AUDIO_MODEL = "google/gemini-3.5-flash-lite";  // spoken requests: hears and decides in one call
    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";

    static final class Result {
        final String say;
        final JSONArray actions;
        final String error;
        final long millis;
        String raw = "";
        String heard = "";
        String interpreter = "";
        int tokensIn, tokensOut;
        double cost;

        Result(String say, JSONArray actions, String error, long millis) {
            this.say = say != null ? say : "";
            this.actions = actions != null ? actions : new JSONArray();
            this.error = error;
            this.millis = millis;
        }
    }

    private final File keyFile;

    VoiceAgent(File keyFile) {
        this.keyFile = keyFile;
    }

    boolean hasKey() {
        return keyFile.exists() && keyFile.length() > 10;
    }

    /** One retry when the reply isn't readable JSON. */
    Result ask(String request, JSONObject context) {
        Result r = askOnce(request, context);
        if (r.error != null && r.error.startsWith("JSON")) {
            Result again = askOnce(request, context);
            if (again.error == null) return again;
        }
        return r;
    }

    /** A spoken request: 16 kHz mono WAV + context to the audio model; returns what it heard plus the plan. */
    Result askAudio(byte[] wav, JSONObject context) {
        Result r = askAudioOnce(wav, context);
        if (r.error != null && r.error.startsWith("JSON")) {
            Result again = askAudioOnce(wav, context);
            if (again.error == null) return again;
        }
        return r;
    }

    private Result askAudioOnce(byte[] wav, JSONObject context) {
        long start = System.currentTimeMillis();
        String content = "";
        try {
            String key = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim();
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", "TV right now:\n" + context.toString()))
                    .put(new JSONObject().put("type", "input_audio").put("input_audio", new JSONObject()
                            .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)).put("format", "wav")));
            JSONObject body = new JSONObject()
                    .put("model", AUDIO_MODEL)
                    .put("max_tokens", 1024)
                    .put("reasoning", new JSONObject().put("effort", "minimal"))
                    .put("usage", new JSONObject().put("include", true))
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", SYSTEM + AUDIO_NOTE))
                            .put(new JSONObject().put("role", "user").put("content", parts)));
            HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("X-Title", "Material TV Launcher voice");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) return new Result(null, null, "OpenRouter said " + code, System.currentTimeMillis() - start);
            JSONObject resp = new JSONObject(text);
            content = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            JSONObject plan = firstObject(content);
            Result r = new Result(plan.optString("say", ""), plan.optJSONArray("actions"), null, System.currentTimeMillis() - start);
            r.heard = plan.optString("heard", "");
            JSONObject usage = resp.optJSONObject("usage");
            if (usage != null) {
                r.tokensIn = usage.optInt("prompt_tokens");
                r.tokensOut = usage.optInt("completion_tokens");
                r.cost = usage.optDouble("cost", 0);
            }
            return r;
        } catch (Exception e) {
            Result r = new Result(null, null, e.getClass().getSimpleName(), System.currentTimeMillis() - start);
            r.raw = content.length() > 400 ? content.substring(0, 400) : content;
            return r;
        }
    }

    private static final String AUDIO_NOTE = "\nThe request comes as AUDIO from a TV microphone (there may be TV sound or "
            + "room noise). First write exactly what the person said in \"heard\" (empty if no one spoke). Reply with exactly "
            + "one JSON object: {\"heard\": \"...\", \"say\": \"...\", \"actions\": [...]}";

    private Result askOnce(String request, JSONObject context) {
        long start = System.currentTimeMillis();
        String content = "";
        try {
            String key = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim();
            JSONObject body = new JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 400)
                    .put("temperature", 0)
                    .put("usage", new JSONObject().put("include", true))
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", SYSTEM))
                            .put(new JSONObject().put("role", "user").put("content",
                                    "TV right now:\n" + context.toString() + "\n\nThe person said: " + request)));
            HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("X-Title", "Material TV Launcher voice");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) return new Result(null, null, "OpenRouter said " + code, System.currentTimeMillis() - start);
            JSONObject resp = new JSONObject(text);
            content = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            JSONObject plan = firstObject(content);
            Result r = new Result(plan.optString("say", ""), plan.optJSONArray("actions"), null, System.currentTimeMillis() - start);
            JSONObject usage = resp.optJSONObject("usage");
            if (usage != null) {
                r.tokensIn = usage.optInt("prompt_tokens");
                r.tokensOut = usage.optInt("completion_tokens");
                r.cost = usage.optDouble("cost", 0);
            }
            return r;
        } catch (Exception e) {
            Result r = new Result(null, null, e.getClass().getSimpleName(), System.currentTimeMillis() - start);
            r.raw = content.length() > 400 ? content.substring(0, 400) : content;
            return r;
        }
    }

    /** The first complete {...} that parses (the model may add text, fences or a second object around it). */
    private static JSONObject firstObject(String s) throws org.json.JSONException {
        for (int a = s.indexOf('{'); a >= 0; a = s.indexOf('{', a + 1)) {
            int depth = 0;
            boolean str = false, esc = false;
            for (int i = a; i < s.length(); i++) {
                char c = s.charAt(i);
                if (str) {
                    if (esc) esc = false;
                    else if (c == '\\') esc = true;
                    else if (c == '"') str = false;
                } else if (c == '"') {
                    str = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    try {
                        JSONObject o = new JSONObject(s.substring(a, i + 1));
                        if (o.has("say") || o.has("actions")) return o;
                    } catch (org.json.JSONException ignored) {
                    }
                    break;
                }
            }
        }
        throw new org.json.JSONException("no plan in reply");
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        return buf.toString("UTF-8");
    }

    private static final String SYSTEM = String.join("\n",
            "You run a TV's home screen by voice. Turn what the person said into actions. Reply with exactly ONE JSON object,",
            "nothing before or after it (several steps go in the same actions list):",
            "{\"say\": \"<what you did, max 50 chars, e.g. Playing Redbone on Spotify>\", \"actions\": [<action>, ...]}",
            "",
            "ACTIONS (use the fewest that do it; packages and inputs only from the context):",
            "- {\"type\": \"open\", \"package\": \"<pkg>\", \"uri\": \"<optional deep link>\"}",
            "  Spotify (com.spotify.tv.android): uri \"spotify:search:<url-encoded query>\" opens search; playback depends on Spotify.",
            "  YouTube (com.google.android.youtube.tv): uri",
            "  \"https://www.youtube.com/results?search_query=<url-encoded query>\". Other apps: omit uri.",
            "- {\"type\": \"input\", \"input_id\": \"<id from inputs>\"}: switch to an HDMI input (\"put on the PS5\").",
            "- {\"type\": \"media\", \"command\": \"play\"|\"pause\"|\"toggle\"|\"next\"|\"previous\"}: the app that's playing.",
            "- {\"type\": \"volume\", \"direction\": \"up\"|\"down\"|\"mute\"|\"unmute\", \"steps\": 1-10}",
            "- {\"type\": \"settings\", \"action\": \"android.settings.<X>\"}: a settings screen (e.g. WIFI_SETTINGS, SOUND_SETTINGS,",
            "  BLUETOOTH_SETTINGS, DISPLAY_SETTINGS, SETTINGS).",
            "- {\"type\": \"home\"}",
            "Music defaults to Spotify, videos to YouTube, unless the person names an app. Follow-ups like \"louder\", \"next\",",
            "\"the other one\" use recent_asks. If it's a question with no action, answer in say (max 80 chars), no actions.",
            "If you can't do it, say so in say with no actions. Never invent packages or input ids.",
            "say describes only what the actions really do (opening an app without a uri is \"Opening X\", not a search).");
}
