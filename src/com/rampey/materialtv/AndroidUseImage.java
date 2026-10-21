package com.rampey.materialtv;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
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
    private AndroidUseImage(String base64, int width, int height, long capturedAt) {
        this.base64 = base64;
        this.width = width;
        this.height = height;
        this.capturedAt = capturedAt;
    }

    static AndroidUseImage capture(AndroidUseService service, BooleanSupplier current) throws InterruptedException {
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
                            width, height, SystemClock.elapsedRealtime());
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
}
