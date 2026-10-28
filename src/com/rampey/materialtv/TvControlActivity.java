package com.rampey.materialtv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

/** User-facing permission entry from Home; visiting it never authorizes control automatically. */
public final class TvControlActivity extends Activity {
    private static final int MICROPHONE = 31, ASSISTANT = 32;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("voice_setup", false)) { voiceSettings(); return; }
        new AlertDialog.Builder(this).setTitle("Settings")
                .setItems(new String[]{"Voice assistant", "TV settings"}, (dialog, choice) -> {
                    if (choice == 0) voiceSettings();
                    else { startActivity(new Intent(Settings.ACTION_SETTINGS)); finish(); }
                }).setOnCancelListener(dialog -> finish()).show();
    }

    private void voiceSettings() {
        boolean selected = AssistantSetup.selected(this);
        new AlertDialog.Builder(this).setTitle("Voice assistant")
                .setItems(new String[]{AssistantSetup.hasKey(this) ? "Change OpenRouter key" : "Add OpenRouter key",
                        selected ? "Gemini voice is selected" : "Enable Gemini voice",
                        "Restore previous assistant"}, (dialog, choice) -> {
                    if (choice == 0) { keyEntry(); return; }
                    if (choice == 1) {
                        if (!AssistantSetup.hasKey(this)) {
                            keyEntry();
                        } else if (selected) note("Gemini voice", "Gemini is already selected. Press the remote mic or Ask.");
                        else new AlertDialog.Builder(this).setTitle("Enable Gemini voice?")
                                .setMessage("Use your OpenRouter key to send spoken requests and TV context to Gemini. "
                                        + "Your previous assistant selection will be saved. Android will ask you to select this launcher as the assistant.")
                                .setNegativeButton("Cancel", (d, w) -> finish())
                                .setPositiveButton("Continue", (d, w) -> enableVoice())
                                .setOnCancelListener(d -> finish()).show();
                    } else restoreVoice();
                }).setOnCancelListener(dialog -> finish()).show();
    }
    private void keyEntry() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("OpenRouter API key");
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setSaveEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("OpenRouter key")
                .setMessage("Use your own OpenRouter key with credit. Spoken requests and TV context go through OpenRouter only when you ask. "
                        + "Saving a key keeps your current assistant; enable Gemini separately.")
                .setView(input)
                .setNegativeButton("Cancel", (d, w) -> voiceSettings())
                .setPositiveButton("Save", null)
                .setOnCancelListener(d -> voiceSettings()).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = input.getText().toString();
            if (!AssistantSetup.validKey(key)) { input.setError("Enter one API key without spaces"); return; }
            try {
                AssistantSetup.saveKey(this, key);
                input.getText().clear();
                dialog.dismiss();
                voiceSettings();
            } catch (Exception error) { input.setError("Couldn't save the key. Your previous key was kept."); }
        }));
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
    private void enableVoice() {
        if (!AssistantSetup.hasKey(this)) { note("Key needed", "Add your OpenRouter key before enabling Gemini."); return; }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MICROPHONE); return;
        }
        try {
            AssistantSetup.remember(this);
            Intent request = AssistantSetup.request(this);
            if (request == null) {
                note("Select your assistant", "This TV does not offer an assistant selection prompt. Use assistant.sh <tv-ip> enable from your computer.");
            } else startActivityForResult(request, ASSISTANT);
        } catch (Exception error) {
            note("Couldn't enable Gemini", "Your assistant selection was not changed. You can use assistant.sh <tv-ip> enable from your computer.");
        }
    }
    private void restoreVoice() {
        if (!AssistantSetup.selected(this)) {
            note("Current assistant kept", "Gemini is not selected. Your current assistant has been left unchanged."); return;
        }
        String previous = AssistantSetup.previousLabel(this);
        new AlertDialog.Builder(this).setTitle("Restore previous assistant")
                .setMessage(previous == null
                        ? "This older installation did not save the previous assistant. Open Android's default-app settings and choose the assistant you want."
                        : "Previous selection: " + previous + ".\n\nOpen Android's default-app settings, choose Digital assistant, then select it. "
                                + "You can also run assistant.sh <tv-ip> restore from your computer to restore the saved selection.")
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setPositiveButton("Open settings", (d, w) -> {
                    try { startActivity(AssistantSetup.chooser(this)); finish(); }
                    catch (Exception unavailable) { note("Assistant settings unavailable", "Run assistant.sh <tv-ip> restore from your computer."); }
                }).setOnCancelListener(d -> finish()).show();
    }
    private void note(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Done", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) enableVoice();
            else note("Microphone not enabled", "Your current assistant stays selected.");
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ASSISTANT) note(AssistantSetup.selected(this) ? "Gemini enabled" : "Current assistant kept",
                AssistantSetup.selected(this) ? "Press the remote mic or Ask to try it. Restore your previous assistant from Settings → Voice assistant."
                        : "Gemini was not selected. Your current assistant has been left unchanged.");
    }
}
