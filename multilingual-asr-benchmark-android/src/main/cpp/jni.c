#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_PKG(name) Java_org_stypox_dicio_asrbenchmark_nativebridge_WhisperNative_##name

static char g_last_error[512] = "";
static char g_last_transcript[64 * 1024] = "";
static char g_last_language[16] = "";
static float g_last_lang_p = -1.0f;
static int g_last_segments = 0;

static void set_error(const char *msg) {
    snprintf(g_last_error, sizeof(g_last_error), "%s", msg ? msg : "unknown error");
    LOGE("%s", g_last_error);
}

static void clear_result(void) {
    g_last_error[0] = '\0';
    g_last_transcript[0] = '\0';
    g_last_language[0] = '\0';
    g_last_lang_p = -1.0f;
    g_last_segments = 0;
}

static struct whisper_context_params cpu_context_params(void) {
    struct whisper_context_params p = whisper_context_default_params();
    p.use_gpu = false;
    p.flash_attn = false;
    return p;
}

static jstring jni_new_utf8(JNIEnv *env, const char *s) {
    if (s == NULL) {
        s = "";
    }
    return (*env)->NewStringUTF(env, s);
}

JNIEXPORT jstring JNICALL
JNI_PKG(systemInfo)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    const char *info = whisper_print_system_info();
    return jni_new_utf8(env, info ? info : "");
}

JNIEXPORT jstring JNICALL
JNI_PKG(lastError)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return jni_new_utf8(env, g_last_error);
}

JNIEXPORT jstring JNICALL
JNI_PKG(lastTranscript)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return jni_new_utf8(env, g_last_transcript);
}

JNIEXPORT jstring JNICALL
JNI_PKG(lastDetectedLanguage)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return jni_new_utf8(env, g_last_language);
}

JNIEXPORT jfloat JNICALL
JNI_PKG(lastLanguageConfidence)(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return g_last_lang_p;
}

JNIEXPORT jint JNICALL
JNI_PKG(lastSegmentCount)(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return g_last_segments;
}

JNIEXPORT jlong JNICALL
JNI_PKG(initContext)(JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void) thiz;
    clear_result();
    if (model_path_str == NULL) {
        set_error("model path is null");
        return 0;
    }
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("whisper_init_from_file %s", model_path);
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cpu_context_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    if (ctx == NULL) {
        set_error("whisper_init_from_file_with_params returned NULL (model load failed)");
        return 0;
    }
    LOGI("whisper context %p", (void *) ctx);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
JNI_PKG(freeContext)(JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void) env;
    (void) thiz;
    if (context_ptr == 0) {
        return;
    }
    whisper_free((struct whisper_context *) context_ptr);
}

static float language_confidence_for_id(struct whisper_context *ctx, int n_threads, int lang_id) {
    if (ctx == NULL || lang_id < 0) {
        return -1.0f;
    }
    const int max_id = whisper_lang_max_id();
    if (lang_id > max_id) {
        return -1.0f;
    }
    float *probs = (float *) calloc((size_t) max_id + 1, sizeof(float));
    if (probs == NULL) {
        return -1.0f;
    }
    /* Best-effort: needs existing mel spectrogram from whisper_full. Failure is valid. */
    int detected = whisper_lang_auto_detect(ctx, 0, n_threads, probs);
    float p = -1.0f;
    if (detected >= 0) {
        p = probs[lang_id];
        if (!isfinite(p) || p < 0.0f) {
            p = -1.0f;
        }
    }
    free(probs);
    return p;
}

JNIEXPORT jint JNICALL
JNI_PKG(fullTranscribe)(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jfloatArray audio_data,
        jstring language_str,
        jstring prompt_str,
        jint n_threads
) {
    (void) thiz;
    clear_result();
    struct whisper_context *ctx = (struct whisper_context *) context_ptr;
    if (ctx == NULL) {
        set_error("whisper context is null");
        return -1;
    }
    if (audio_data == NULL) {
        set_error("audio_data is null");
        return -2;
    }
    if (n_threads < 1) {
        n_threads = 1;
    }

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);
    if (samples == NULL || n_samples <= 0) {
        if (samples) {
            (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
        }
        set_error("empty audio buffer");
        return -3;
    }

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }
    const char *prompt = NULL;
    if (prompt_str != NULL) {
        prompt = (*env)->GetStringUTFChars(env, prompt_str, NULL);
        if (prompt != NULL && prompt[0] == '\0') {
            (*env)->ReleaseStringUTFChars(env, prompt_str, prompt);
            prompt = NULL;
            prompt_str = NULL;
        }
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false; /* transcription only — never translation */
    params.language = (language != NULL && language[0] != '\0') ? language : "auto";
    params.detect_language = false;
    params.n_threads = n_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.no_timestamps = true;
    params.vad = false;
    params.initial_prompt = prompt;

    LOGI("whisper_full n_samples=%d n_threads=%d language=%s prompt=%s",
         (int) n_samples,
         n_threads,
         params.language ? params.language : "auto",
         params.initial_prompt ? "on" : "off");

    whisper_reset_timings(ctx);
    const int rc = whisper_full(ctx, params, samples, n_samples);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);

    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    if (prompt != NULL && prompt_str != NULL) {
        (*env)->ReleaseStringUTFChars(env, prompt_str, prompt);
    }

    if (rc != 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "whisper_full failed with code %d", rc);
        set_error(buf);
        return rc;
    }

    const int n_seg = whisper_full_n_segments(ctx);
    g_last_segments = n_seg;
    size_t used = 0;
    g_last_transcript[0] = '\0';
    for (int i = 0; i < n_seg; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg == NULL) {
            continue;
        }
        const size_t len = strlen(seg);
        if (used + len + 1 >= sizeof(g_last_transcript)) {
            set_error("raw transcript exceeded JNI buffer (output truncated)");
            break;
        }
        memcpy(g_last_transcript + used, seg, len);
        used += len;
        g_last_transcript[used] = '\0';
    }

    const int lang_id = whisper_full_lang_id(ctx);
    const char *lang = whisper_lang_str(lang_id);
    if (lang != NULL) {
        snprintf(g_last_language, sizeof(g_last_language), "%s", lang);
    } else {
        g_last_language[0] = '\0';
    }
    /* Confidence is probed AFTER the caller stops the transcription timer. */
    g_last_lang_p = -1.0f;

    LOGI("whisper_full ok segments=%d lang=%s bytes=%zu", n_seg, g_last_language, used);
    return 0;
}

JNIEXPORT jfloat JNICALL
JNI_PKG(probeLanguageConfidence)(JNIEnv *env, jobject thiz, jlong context_ptr, jint n_threads) {
    (void) env;
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) context_ptr;
    if (ctx == NULL) {
        g_last_lang_p = -1.0f;
        return g_last_lang_p;
    }
    const int lang_id = whisper_full_lang_id(ctx);
    g_last_lang_p = language_confidence_for_id(ctx, n_threads, lang_id);
    return g_last_lang_p;
}
