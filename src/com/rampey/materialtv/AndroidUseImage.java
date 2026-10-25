package com.rampey.materialtv;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Optional screen image for one explicit Android-use step. Never written to disk. */
final class AndroidUseImage {
    final String base64;
    final int width, height;
    final long capturedAt;
    final String progressToken;
    private AndroidUseImage(String base64, int width, int height, long capturedAt, String progressToken) {
        this.base64 = base64;
        this.width = width;
        this.height = height;
        this.capturedAt = capturedAt;
        this.progressToken = progressToken;
    }

    static AndroidUseImage capture(AndroidUseService service, BooleanSupplier current) throws InterruptedException {
        return capture(service, current, null);
    }

    static AndroidUseImage capture(AndroidUseService service, BooleanSupplier current, Rect assistantBounds) throws InterruptedException {
        if (!current.getAsBoolean()) return null;
        CountDownLatch done = new CountDownLatch(1);
        AndroidUseImage[] frame = {null};
        service.takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run, new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hardware = null, software = null, scaled = null;
                try {
                    if (!current.getAsBoolean()) return;
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) return;
                    software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    int width = software.getWidth(), height = software.getHeight();
                    float scale = Math.min(1f, 1280f / width);
                    scaled = Bitmap.createScaledBitmap(software, Math.round(width * scale), Math.round(height * scale), true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes);
                    if (current.getAsBoolean() && bytes.size() <= 1_500_000) frame[0] = new AndroidUseImage(
                            android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP),
                            width, height, SystemClock.elapsedRealtime(), progressToken(scaled, width, height, assistantBounds));
                } catch (RuntimeException unavailable) {
                    android.util.Log.w("MaterialTvAndroidUse", "screen image unavailable");
                } finally {
                    if (scaled != null && scaled != software) scaled.recycle();
                    if (software != null) software.recycle();
                    if (hardware != null) hardware.recycle();
                    buffer.close();
                    done.countDown();
                }
            }
            @Override public void onFailure(int error) {
                android.util.Log.w("MaterialTvAndroidUse", "screen image error=" + error);
                done.countDown();
            }
        });
        if (!done.await(3, TimeUnit.SECONDS) || !current.getAsBoolean()) return null;
        return frame[0];
    }

    /** Compare coarse app pixels, never the assistant's animated orb/progress text or JPEG encoding noise. */
    static String progressToken(Bitmap image, int displayWidth, int displayHeight, Rect assistantBounds) {
        StringBuilder token = new StringBuilder(64 * 36);
        for (int row = 0; row < 36; row++) for (int col = 0; col < 64; col++) {
            int x = (2 * col + 1) * image.getWidth() / 128;
            int y = (2 * row + 1) * image.getHeight() / 72;
            int screenX = x * displayWidth / image.getWidth(), screenY = y * displayHeight / image.getHeight();
            if (assistantBounds != null && assistantBounds.contains(screenX, screenY)) { token.append('_'); continue; }
            int pixel = image.getPixel(x, y);
            token.append((char) (((pixel >> 21) & 7) << 6 | ((pixel >> 13) & 7) << 3 | ((pixel >> 5) & 7)));
        }
        return token.toString();
    }
}
