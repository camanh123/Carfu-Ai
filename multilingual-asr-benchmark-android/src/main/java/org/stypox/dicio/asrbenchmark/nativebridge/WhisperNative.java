package org.stypox.dicio.asrbenchmark.nativebridge;

/**
 * JNI surface over official ggml-org/whisper.cpp.
 * Keep this class name stable: native symbols are generated from it.
 */
public final class WhisperNative {
    static {
        System.loadLibrary("whisper");
    }

    private WhisperNative() {
    }

    public static native String systemInfo();

    public static native String lastError();

    public static native String lastTranscript();

    public static native String lastDetectedLanguage();

    public static native float lastLanguageConfidence();

    public static native int lastSegmentCount();

    public static native long initContext(String modelPath);

    public static native void freeContext(long contextPtr);

    /**
     * @param language "auto" or a whisper language code such as "vi"
     * @param prompt   null/empty for no initial_prompt
     * @return 0 on success, non-zero whisper_full code otherwise
     */
    public static native int fullTranscribe(
            long contextPtr,
            float[] audioData,
            String language,
            String prompt,
            int nThreads
    );

    /**
     * Best-effort language probability after transcribe. May re-run encoder; call outside RTF timer.
     * @return probability in [0,1] or negative if unavailable
     */
    public static native float probeLanguageConfidence(long contextPtr, int nThreads);
}
