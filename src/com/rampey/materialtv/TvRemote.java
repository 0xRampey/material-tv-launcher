package com.rampey.materialtv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Consented loopback-only TV remote. No caller can supply a host or shell command. */
final class TvRemote {
    private static final Handler TIMER = new Handler(Looper.getMainLooper());
    private static final Object KEYS = new Object();
    private static final Object SESSION = new Object();
    private static AdbConnection authorized;
    private static Socket authorizedSocket;
    private static final Runnable EXPIRE = TvRemote::closeSession;
    private static void closeSession() {
        synchronized (SESSION) {
            try { if (authorizedSocket != null) authorizedSocket.close(); } catch (Exception ignored) { }
            authorized = null;
            authorizedSocket = null;
        }
    }
    static boolean enabled(Context app) {
        return app.getSharedPreferences("voice", 0).getBoolean("tv_remote", false);
    }
    static void disable(Context app) {
        app.getSharedPreferences("voice", 0).edit().putBoolean("tv_remote", false).apply();
        TIMER.removeCallbacks(EXPIRE);
        closeSession();
    }
    static int keyCode(String name) {
        switch (name) {
            case "up": return 19;
            case "down": return 20;
            case "left": return 21;
            case "right": return 22;
            case "ok": return 23;
            default: return -1;
        }
    }
    static String authorize(Context app, BooleanSupplier current) {
        return execute(app, null, current, true);
    }
    static String press(Context app, String name, BooleanSupplier current) {
        if (keyCode(name) < 0) return "Choose up, down, left, right or ok";
        if (!enabled(app)) return "Enable TV remote control from Home settings first";
        return execute(app, name, () -> current.getAsBoolean() && enabled(app), false);
    }
    private static AdbCrypto crypto(Context app) throws Exception {
        synchronized (KEYS) {
            File dir = new File(app.getNoBackupFilesDir(), "tv-remote");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Key storage unavailable");
            File priv = new File(dir, "private"), pub = new File(dir, "public");
            com.tananaev.adblib.AdbBase64 codec = data -> Base64.encodeToString(data, Base64.NO_WRAP);
            if (priv.isFile() && pub.isFile()) return AdbCrypto.loadAdbKeyPair(codec, priv, pub);
            AdbCrypto key = AdbCrypto.generateAdbKeyPair(codec);
            key.saveAdbKeyPair(priv, pub);
            return key;
        }
    }
    private static String execute(Context app, String name, BooleanSupplier current, boolean consent) {
        synchronized (SESSION) { return executeLocked(app, name, current, consent); }
    }
    private static String executeLocked(Context app, String name, BooleanSupplier current, boolean consent) {
        if (!current.getAsBoolean()) return "cancelled";
        Socket socket = authorizedSocket == null ? new Socket() : authorizedSocket;
        boolean keep = false;
        long until = android.os.SystemClock.elapsedRealtime() + (consent ? 60000 : 4500);
        Runnable watchdog = new Runnable() {
            @Override public void run() {
                if (!current.getAsBoolean() || android.os.SystemClock.elapsedRealtime() >= until) {
                    try { socket.close(); } catch (Exception ignored) { }
                } else TIMER.postDelayed(this, 100);
            }
        };
        TIMER.post(watchdog);
        try {
            if (!current.getAsBoolean()) return "cancelled";
            AdbConnection connection = authorized;
            if (connection == null) {
                AdbCrypto key = crypto(app);
                socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
                connection = AdbConnection.create(socket, key);
                if (!connection.connect(consent ? 55 : 2, TimeUnit.SECONDS, !consent))
                    return "TV remote authorization timed out; open Home settings";
            }
            {
                if (!current.getAsBoolean()) return "cancelled";
                authorized = connection;
                authorizedSocket = socket;
                keep = true;
                TIMER.removeCallbacks(EXPIRE);
                TIMER.postDelayed(EXPIRE, 10 * 60_000);
                if (consent) {
                    app.getSharedPreferences("voice", 0).edit().putBoolean("tv_remote", true).commit();
                    return null;
                }
                // The complete command is built from a fixed integer allowlist, never model text.
                String command = "shell:input keyevent " + keyCode(name) + "; echo TV_REMOTE_OK:$?";
                try (AdbStream stream = connection.open(command)) {
                    StringBuilder output = new StringBuilder();
                    while (!stream.isClosed() && output.length() < 1024 && current.getAsBoolean()) {
                        try { output.append(new String(stream.read(), StandardCharsets.UTF_8)); }
                        catch (java.io.IOException closed) { break; }
                    }
                    return output.toString().contains("TV_REMOTE_OK:0") ? null : "TV did not confirm the remote key";
                }
            }
        } catch (com.tananaev.adblib.AdbAuthenticationFailedException denied) {
            keep = false;
            return "TV remote consent is needed; open Home settings";
        } catch (Exception failure) {
            keep = false;
            return current.getAsBoolean() ? "TV remote unavailable; check debugging and Home settings" : "cancelled";
        } finally {
            TIMER.removeCallbacks(watchdog);
            if (!keep) {
                try { socket.close(); } catch (Exception ignored) { }
                authorized = null;
                authorizedSocket = null;
            }
        }
    }
}
