#include <jni.h>
#include <whisper.h>
#include <fstream>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <thread>
#include <memory>
#include <cstdint>

static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_daybook_app_capture_WhisperEngine_transcribeNative(JNIEnv *env, jobject, jstring model, jstring audio) {
    const char *m = env->GetStringUTFChars(model, nullptr);
    const char *a = env->GetStringUTFChars(audio, nullptr);
    std::string modelPath(m), audioPath(a);
    env->ReleaseStringUTFChars(model, m); env->ReleaseStringUTFChars(audio, a);
    try {
        std::ifstream input(audioPath, std::ios::binary);
        if (!input) { fail(env, "Saved audio could not be opened."); return nullptr; }
        char header[44]; input.read(header, 44);
        if (input.gcount() != 44 || std::string(header, 4) != "RIFF" || std::string(header + 8, 4) != "WAVE" ||
            static_cast<uint8_t>(header[22]) != 1 || static_cast<uint8_t>(header[34]) != 16) {
            fail(env, "Unsupported audio. Daybook expects mono 16 kHz PCM WAV."); return nullptr;
        }
        uint32_t rate = static_cast<uint8_t>(header[24]) | (static_cast<uint8_t>(header[25]) << 8) |
            (static_cast<uint8_t>(header[26]) << 16) | (static_cast<uint8_t>(header[27]) << 24);
        if (rate != 16000) { fail(env, "Unsupported recording sample rate."); return nullptr; }
        auto cp = whisper_context_default_params(); cp.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(modelPath.c_str(), cp), whisper_free);
        if (!ctx) { fail(env, "Speech model could not load. Delete and download it again in Settings."); return nullptr; }
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        params.language = "en"; params.translate = false; params.no_context = true;
        params.no_timestamps = true; params.single_segment = false; params.suppress_blank = true; params.suppress_nst = true;
        params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
        std::vector<int16_t> pcm(16000 * 30);
        std::vector<float> samples(pcm.size());
        std::string result;
        while (input) {
            input.read(reinterpret_cast<char*>(pcm.data()), static_cast<std::streamsize>(pcm.size() * 2));
            const auto count = static_cast<size_t>(input.gcount()) / 2;
            if (count == 0) break;
            double energy = 0;
            for (size_t i = 0; i < count; ++i) { samples[i] = pcm[i] / 32768.f; energy += samples[i] * samples[i]; }
            if (energy / count < 0.000001) continue;
            if (whisper_full(ctx.get(), params, samples.data(), static_cast<int>(count)) != 0) {
                fail(env, "Offline transcription failed. Your original audio is preserved."); return nullptr;
            }
            for (int i = 0; i < whisper_full_n_segments(ctx.get()); ++i) {
                const char *segment = whisper_full_get_segment_text(ctx.get(), i);
                if (segment) result += segment;
            }
            result += "\n";
        }
        // NewStringUTF accepts modified UTF-8. Construct a Java UTF-8 String for arbitrary names and emoji.
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(result.size()));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte*>(result.data()));
        jclass stringClass = env->FindClass("java/lang/String");
        jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
        jstring encoding = env->NewStringUTF("UTF-8");
        return static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, encoding));
    } catch (const std::exception &) {
        fail(env, "Offline speech processing failed. The original recording is preserved.");
        return nullptr;
    }
}


static jstring utf8(JNIEnv *env, const std::string &text) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    jclass cls = env->FindClass("java/lang/String");
    return static_cast<jstring>(env->NewObject(cls, env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V"), bytes, env->NewStringUTF("UTF-8")));
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_daybook_app_capture_WhisperEngine_openPreviewNative(JNIEnv *env, jobject, jstring model) {
    try {
    const char *path = env->GetStringUTFChars(model, nullptr);
    if (!path) return 0;
    std::string value(path); env->ReleaseStringUTFChars(model, path);
    auto params = whisper_context_default_params(); params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(value.c_str(), params);
    if (!ctx) { fail(env, "The live speech model could not load."); return 0; }
    return reinterpret_cast<jlong>(ctx);
    } catch (const std::exception &) { fail(env, "The live speech model could not load. Audio is preserved."); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_daybook_app_capture_WhisperEngine_previewNative(JNIEnv *env, jobject, jlong handle, jfloatArray audio) {
    auto *ctx = reinterpret_cast<whisper_context*>(handle);
    const auto length = env->GetArrayLength(audio);
    if (!ctx || length <= 0 || length > 16000 * 16) { fail(env, "Invalid live audio window."); return nullptr; }
    try {
        std::vector<float> samples(length);
        env->GetFloatArrayRegion(audio, 0, length, samples.data());
        double energy = 0; for (float sample : samples) energy += sample * sample;
        if (energy / length < 0.000001) return utf8(env, "");
        auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads = std::max(1u, std::min(3u, std::thread::hardware_concurrency()));
        p.language = "en"; p.translate = false; p.no_context = true; p.no_timestamps = true;
        p.suppress_blank = true; p.suppress_nst = true;
        p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
        if (whisper_full(ctx, p, samples.data(), length) != 0) { fail(env, "Live transcription failed."); return nullptr; }
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) { const char *segment = whisper_full_get_segment_text(ctx, i); if (segment) text += segment; }
        return utf8(env, text);
    } catch (const std::exception &) { fail(env, "Live transcription failed. Audio is preserved."); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_daybook_app_capture_WhisperEngine_closePreviewNative(JNIEnv *, jobject, jlong handle) { if (handle) whisper_free(reinterpret_cast<whisper_context*>(handle)); }

struct AbortControl {
    JNIEnv *owner; JavaVM *vm = nullptr; jobject control; jmethodID method;
    AbortControl(JNIEnv *env, jobject value): owner(env), control(env->NewGlobalRef(value)) {
        env->GetJavaVM(&vm); jclass cls = env->GetObjectClass(value);
        method = env->GetMethodID(cls, "isCancelled", "()Z"); env->DeleteLocalRef(cls);
    }
    ~AbortControl() { owner->DeleteGlobalRef(control); }
};
static bool shouldAbort(void *opaque) {
    auto *c = static_cast<AbortControl*>(opaque); JNIEnv *env = nullptr; bool attached = false;
    if (c->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (c->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true; attached = true;
    }
    bool cancelled = env->CallBooleanMethod(c->control, c->method);
    if (env->ExceptionCheck()) { env->ExceptionClear(); cancelled = true; }
    if (attached) c->vm->DetachCurrentThread();
    return cancelled;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_daybook_app_capture_WhisperEngine_timelineNative(JNIEnv *env, jobject, jstring model, jstring audio, jobject control) {
    const char *m = env->GetStringUTFChars(model, nullptr);
    const char *a = env->GetStringUTFChars(audio, nullptr);
    std::string modelPath(m), audioPath(a);
    env->ReleaseStringUTFChars(model, m); env->ReleaseStringUTFChars(audio, a);
    try {
        std::ifstream input(audioPath, std::ios::binary);
        if (!input) { fail(env, "Saved audio could not be opened."); return nullptr; }
        char header[44]; input.read(header, 44);
        if (input.gcount() != 44 || std::string(header, 4) != "RIFF" || std::string(header + 8, 4) != "WAVE" ||
            static_cast<uint8_t>(header[22]) != 1 || static_cast<uint8_t>(header[34]) != 16) {
            fail(env, "Unsupported audio. Daybook expects mono 16 kHz PCM WAV."); return nullptr;
        }
        uint32_t rate = static_cast<uint8_t>(header[24]) | (static_cast<uint8_t>(header[25]) << 8) |
            (static_cast<uint8_t>(header[26]) << 16) | (static_cast<uint8_t>(header[27]) << 24);
        if (rate != 16000) { fail(env, "Unsupported recording sample rate."); return nullptr; }
        auto cp = whisper_context_default_params(); cp.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(modelPath.c_str(), cp), whisper_free);
        if (!ctx) { fail(env, "Speech model could not load. Delete and download it again in Settings."); return nullptr; }
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        params.language = "en"; params.translate = false; params.no_context = true;
        params.no_timestamps = false; params.single_segment = false; params.suppress_blank = true; params.suppress_nst = true;
        params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
        std::vector<int16_t> pcm(16000 * 30);
        std::vector<float> samples(pcm.size());
        std::vector<std::string> result;
        int64_t offset = 0;
        AbortControl abort(env, control);
        params.abort_callback = shouldAbort; params.abort_callback_user_data = &abort;
        while (input) {
            input.read(reinterpret_cast<char*>(pcm.data()), static_cast<std::streamsize>(pcm.size() * 2));
            const auto count = static_cast<size_t>(input.gcount()) / 2;
            if (count == 0) break;
            double energy = 0;
            for (size_t i = 0; i < count; ++i) { samples[i] = pcm[i] / 32768.f; energy += samples[i] * samples[i]; }
            if (energy / count < 0.000001) { offset += static_cast<int64_t>(count) * 1000 / 16000; continue; }
            if (whisper_full(ctx.get(), params, samples.data(), static_cast<int>(count)) != 0) {
                fail(env, "Offline transcription failed. Your original audio is preserved."); return nullptr;
            }
            for (int i = 0; i < whisper_full_n_segments(ctx.get()); ++i) {
                const char *segment = whisper_full_get_segment_text(ctx.get(), i);
                const int64_t end = offset + static_cast<int64_t>(count) * 1000 / 16000;
                const int64_t from = std::clamp<int64_t>(offset + whisper_full_get_segment_t0(ctx.get(), i) * 10, offset, end);
                const int64_t to = std::clamp<int64_t>(offset + whisper_full_get_segment_t1(ctx.get(), i) * 10, from, end);
                if (segment) result.push_back(std::to_string(from) + "\t" + std::to_string(to) + "\t" + segment);
            }
            offset += static_cast<int64_t>(count) * 1000 / 16000;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(result.size()), env->FindClass("java/lang/String"), nullptr);
        for (size_t i = 0; i < result.size(); ++i) {
            jstring value = utf8(env, result[i]); env->SetObjectArrayElement(array, static_cast<jsize>(i), value); env->DeleteLocalRef(value);
        }
        return array;
    } catch (const std::exception &) {
        fail(env, "Offline speech processing failed. The original recording is preserved.");
        return nullptr;
    }
}
