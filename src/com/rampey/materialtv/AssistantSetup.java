package com.rampey.materialtv;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Saves the selection before consent. Only Android's chooser or an explicit ADB command changes roles. */
final class AssistantSetup {
    private static final String[] FIELDS = {"assistant", "voice_interaction_service", "voice_recognition_service"};
    static boolean hasKey(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir != null && new VoiceAgent(new File(dir, "openrouter_key")).hasKey();
    }
    static boolean validKey(String value) {
        if (value == null) return false;
        String key = value.trim();
        if (key.length() <= 10 || key.length() >= 4096) return false;
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) < 33 || key.charAt(i) > 126) return false;
        return true;
    }
    static void saveKey(Context context, String value) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) throw new IllegalStateException("TV storage is unavailable");
        storeKey(new File(dir, "openrouter_key"), value);
    }
    static void storeKey(File target, String value) throws Exception {
        if (!validKey(value)) throw new IllegalArgumentException("Enter one OpenRouter key without spaces");
        AtomicFile file = new AtomicFile(target);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(value.trim().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception error) {
            file.failWrite(stream);
            throw error;
        }
    }
    static boolean selected(Context context) {
        return VoiceInteractionService.isActiveService(context, new ComponentName(context, AssistService.class));
    }
    static File backup(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) throw new IllegalStateException("TV storage is unavailable");
        return new File(dir, "previous_assistant.json");
    }
    static void remember(Context context) throws Exception {
        // Updating or re-enabling Gemini must never replace the backup with Gemini itself.
        if (selected(context)) return;
        JSONObject settings = new JSONObject();
        String packageName = "";
        for (String field : FIELDS) {
            String value = Settings.Secure.getString(context.getContentResolver(), field);
            if (value != null && value.startsWith(context.getPackageName() + "/"))
                throw new IllegalStateException("An older Gemini setup has no recoverable previous selection");
            settings.put(field, value == null ? JSONObject.NULL : value);
            if (packageName.isEmpty() && !field.equals("voice_recognition_service")) {
                ComponentName component = ComponentName.unflattenFromString(value == null ? "" : value);
                if (component != null) packageName = component.getPackageName();
            }
        }
        // Public apps cannot enumerate another app's role holders. The saved component identifies the
        // assistant package on supported TVs; ADB captures the exact role directly when used to enable.
        JSONArray holders = new JSONArray();
        if (!packageName.isEmpty()) holders.put(packageName);
        // Preview setup and the accompanying ADB installer target Android's primary TV user.
        if (android.os.Process.myUid() >= 100000)
            throw new IllegalStateException("Assistant setup supports the primary TV user only");
        JSONObject state = new JSONObject().put("version", 1).put("user", 0)
                .put("holders", holders).put("settings", settings);
        File target = backup(context), temp = new File(target.getPath() + ".tmp");
        Files.write(temp.toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    static String previousLabel(Context context) {
        try {
            JSONObject settings = new JSONObject(new String(Files.readAllBytes(backup(context).toPath()), StandardCharsets.UTF_8)).getJSONObject("settings");
            for (String field : FIELDS) {
                if (field.equals("voice_recognition_service")) continue;
                ComponentName component = ComponentName.unflattenFromString(settings.optString(field, ""));
                if (component == null || component.getPackageName().equals(context.getPackageName())) continue;
                try {
                    return context.getPackageManager().getApplicationLabel(
                            context.getPackageManager().getApplicationInfo(component.getPackageName(), 0)).toString();
                } catch (Exception unavailable) { return component.getPackageName(); }
            }
            return "None";
        } catch (Exception missing) { return null; }
    }
    static Intent chooser(Context context) {
        Intent intent = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
        if (intent.resolveActivity(context.getPackageManager()) == null)
            intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        return intent;
    }
    static Intent request(Context context) {
        RoleManager roles = context.getSystemService(RoleManager.class);
        if (roles == null || !roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null;
        return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT);
    }
}
