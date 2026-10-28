package com.k2fsa.sherpa.onnx;

/** Android: the libraries ship inside the APK (lib/armeabi-v7a), so System.loadLibrary finds them. */
public class LibraryLoader {
    private static volatile boolean isLoaded = false;

    static synchronized void loadLibrary() {
        if (!isLoaded) {
            System.loadLibrary("onnxruntime");
            System.loadLibrary("sherpa-onnx-jni");
            isLoaded = true;
        }
    }

    public static void setAutoLoadEnabled(boolean enabled) {
    }

    static void maybeLoad() {
        loadLibrary();
    }
}
