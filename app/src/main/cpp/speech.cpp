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
