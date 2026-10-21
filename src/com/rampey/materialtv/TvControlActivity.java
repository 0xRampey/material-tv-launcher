package com.rampey.materialtv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-facing permission entry from Home; visiting it never authorizes control automatically. */
public final class TvControlActivity extends Activity {
    private final AtomicBoolean current = new AtomicBoolean(true);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new AlertDialog.Builder(this).setTitle("TV control")
                .setItems(new String[]{"Android use accessibility permission",
                        TvRemote.enabled(this) ? "Reconnect TV remote control" : "Authorize TV remote control",
                        "Disable TV remote control", "TV settings"}, (dialog, choice) -> {
                    if (choice == 0) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); finish(); }
                    else if (choice == 1) consent();
                    else if (choice == 2) { TvRemote.disable(this); finish(); }
                    else { startActivity(new Intent(Settings.ACTION_SETTINGS)); finish(); }
                }).setOnCancelListener(dialog -> finish()).show();
    }
    private void consent() {
        new AlertDialog.Builder(this).setTitle("Allow launcher remote control?")
                .setMessage("This lets the launcher send arrows and OK during your Android-use requests. "
                        + "It connects only to this TV through local debugging. No computer or companion app is needed.\n\n"
                        + "Debugging grants broad device access. Enable network debugging in TV settings, then approve "
                        + "the Android debugging prompt. Only authorize devices you trust. You can disable this here later.")
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setPositiveButton("Connect", (d, w) -> connect()).setOnCancelListener(d -> finish()).show();
    }
    private void connect() {
        AlertDialog waiting = new AlertDialog.Builder(this).setTitle("Waiting for TV consent")
                .setMessage("Approve the Android debugging prompt. One-time access is enough; leave Always allow unchecked. "
                        + "The connection closes after ten minutes without a control request.")
                .setNegativeButton("Cancel", (d, w) -> { current.set(false); finish(); }).create();
        waiting.setOnCancelListener(d -> { current.set(false); finish(); });
        waiting.show();
        new Thread(() -> {
            String error = TvRemote.authorize(this, current::get);
            runOnUiThread(() -> {
                if (!current.get() || isFinishing()) return;
                waiting.dismiss();
                new AlertDialog.Builder(this).setTitle(error == null ? "TV remote ready" : "Couldn't connect")
                        .setMessage(error == null ? "Arrows and OK are ready for Android use. Press Home to return." : error)
                        .setPositiveButton("Done", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
            });
        }, "tv-remote-consent").start();
    }
    @Override protected void onDestroy() { current.set(false); super.onDestroy(); }
}
