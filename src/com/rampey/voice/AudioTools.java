package com.rampey.voice;

import java.util.Locale;

/** Small pieces of the clean-up: a high-pass filter, the repeat collapser, a WAV wrapper. */
public final class AudioTools {
    private AudioTools() {
    }

    /** In-place 2nd-order Butterworth high-pass at fc. */
    public static void highPass(float[] x, float fc, int rate) {
        double w = 2 * Math.PI * fc / rate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        double a0 = 1 + alpha, b0 = (1 + cos) / 2 / a0, b1 = -(1 + cos) / a0, b2 = b0, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double y = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y;
            x[i] = (float) y;
        }
    }

    /** "okay okay okay okay" -> "okay": a word said three or more times in a row is a model loop, not speech. */
    public static String collapseRepeats(String text) {
        String[] words = text.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String key = words[i].replaceAll("[^\\p{L}\\p{N}']", "").toLowerCase(Locale.ROOT);
            int j = i + 1;
            while (j < words.length && !key.isEmpty()
                    && words[j].replaceAll("[^\\p{L}\\p{N}']", "").toLowerCase(Locale.ROOT).equals(key)) j++;
            int run = j - i;
            int keep = run >= 3 ? 1 : run;
            for (int k = 0; k < keep; k++) out.append(out.length() > 0 ? " " : "").append(words[i + k]);
            i = j;
        }
        return out.toString();
    }

    /** 16-bit mono PCM (little-endian) wrapped as a WAV file. */
    public static byte[] wav(byte[] pcm, int rate) {
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
