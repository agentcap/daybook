#include <jni.h>
#include <algorithm>
#include <cmath>
#include "llama.h"
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <sched.h>
#if defined(__x86_64__)
#include <cpuid.h>
#endif

namespace {
using Clock = std::chrono::steady_clock;
struct Control {
    JNIEnv * env; jobject object; jmethodID cancelled;
    Clock::time_point deadline = Clock::now() + std::chrono::minutes(8);
    void report(const char * stage, int prompt, int output, Clock::time_point started) {
        auto method = env->GetMethodID(env->GetObjectClass(object), "progress", "(Ljava/lang/String;IIJ)V");
        auto name = env->NewStringUTF(stage);
        env->CallVoidMethod(object, method, name, prompt, output,
            static_cast<jlong>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count()));
        env->DeleteLocalRef(name);
    }
    void check() {
        if (Clock::now() >= deadline) throw std::runtime_error("On-device cleanup took too long. Try a shorter day or use OpenAI.");
        if (env->CallBooleanMethod(object, cancelled)) throw std::runtime_error("On-device cleanup was stopped. Your originals are saved.");
        if (env->ExceptionCheck()) throw std::runtime_error("On-device cleanup was interrupted.");
    }
};
bool expired(void * data) { return Clock::now() >= static_cast<Control *>(data)->deadline; }
std::string bytes(JNIEnv * env, jbyteArray input) {
    const auto n = env->GetArrayLength(input);
    std::string result(n, '\0');
    env->GetByteArrayRegion(input, 0, n, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
void quiet(enum ggml_log_level, const char *, void *) {}
struct Runtime {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    llama_batch_ext * batch = nullptr;
    ~Runtime() {
        if (batch) llama_batch_ext_free(batch);
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
llama_token sample(Runtime & r, const llama_vocab * vocab) {
    const int size = llama_vocab_n_tokens(vocab);
    const float * logits = llama_get_logits_ith(r.context, -1);
    const auto best = static_cast<llama_token>(std::max_element(logits, logits + size) - logits);
    llama_token_data candidate{best, logits[best], 0.0f};
    llama_token_data_array single{&candidate, 1, -1, false};
    // Most generated tokens already satisfy the grammar. Check that token first
    // rather than masking the full vocabulary on every decoding step.
    llama_sampler_apply(r.sampler, &single);
    llama_token chosen = best;
    if (!std::isfinite(candidate.logit)) {
        std::vector<llama_token_data> all(size);
        for (int i = 0; i < size; ++i) all[i] = {i, logits[i], 0.0f};
        llama_token_data_array candidates{all.data(), all.size(), -1, false};
        llama_sampler_apply(r.sampler, &candidates);
        auto it = std::max_element(all.begin(), all.end(), [](auto & a, auto & b) { return a.logit < b.logit; });
        if (!std::isfinite(it->logit)) throw std::runtime_error("The local model could not produce a valid review.");
        chosen = it->id;
    }
    llama_sampler_accept(r.sampler, chosen);
    return chosen;
}
void batch_tokens(Runtime & r, const llama_token * tokens, int n, int pos) {
    llama_batch_ext_clear(r.batch);
    for (int i = 0; i < n; ++i) {
        int index = llama_batch_ext_add_token(r.batch, 0, tokens[i]);
        if (index < 0) throw std::runtime_error("Could not prepare the local model input.");
        llama_pos position = pos + i;
        llama_batch_ext_set_pos(r.batch, index, &position);
    }
    llama_batch_ext_set_output_logits(r.batch, n - 1, true);
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_daybook_app_ai_LocalEngine_generateNative(JNIEnv * env, jobject, jstring path,
    jbyteArray system_bytes, jbyteArray user_bytes, jbyteArray grammar_bytes, jobject control_object) {
    try {
#if defined(__x86_64__)
        unsigned a, b, c, d;
        bool supported = __get_cpuid(1, &a, &b, &c, &d) && (c & bit_AVX) && (c & bit_FMA) && (c & bit_F16C) && (c & bit_OSXSAVE);
        unsigned xcr_low = 0, xcr_high = 0;
        if (supported) __asm__ volatile("xgetbv" : "=a"(xcr_low), "=d"(xcr_high) : "c"(0));
        supported = supported && (xcr_low & 6) == 6 && __get_cpuid_count(7, 0, &a, &b, &c, &d) && (b & bit_AVX2);
        if (!supported) throw std::runtime_error("On-device reviews on an x86 emulator need an AVX2-capable host. Use the ARM64 phone build or OpenAI.");
#endif
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_log_set(quiet, nullptr); llama_backend_init(); });
        Control control{env, control_object, env->GetMethodID(env->GetObjectClass(control_object), "isCancelled", "()Z")};
        const auto started = Clock::now();
        control.check();
        control.report("Loading model", 0, 0, started);
        const char * model_path = env->GetStringUTFChars(path, nullptr);
        if (!model_path) return nullptr;
        const std::string model_file(model_path);
        env->ReleaseStringUTFChars(path, model_path);
        Runtime r;
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        r.model = llama_model_load_from_file(model_file.c_str(), mp);
        if (!r.model) throw std::runtime_error("Could not load the on-device model. Remove it in Settings and download it again.");
        control.check();
        const auto system = bytes(env, system_bytes), user = bytes(env, user_bytes), grammar = bytes(env, grammar_bytes);
        // A complete worked example teaches the small model's extraction format.
        // It is a distinct conversation turn, never part of the journal's sources.
        const char * example_input = R"json({"date":"2026-01-04","entries":[{"id":"example-note","text":"I took a walk. I want to learn guitar. I need to buy strings. Maybe I could try watercolor painting."}],"goals":[{"id":"walk","title":"Take a walk","period":"daily","checklist":true,"target":1,"unit":"times","archived":false}],"confirmedActivities":[],"existingItems":[]})json";
        const char * example_output = R"json({"journal":"I took a walk today. I want to learn guitar, need to buy strings, and might try watercolor painting.","summary":"A walk today, with music and painting plans for later.","captions":[],"goalActivity":[{"goalId":"walk","workedOn":true,"amount":1,"note":"Took a walk.","sources":["example-note"],"estimated":false}],"newGoals":{"found":true,"items":[{"title":"Learn guitar","details":"I want to learn guitar.","sources":["example-note"],"period":"once","target":1,"unit":"times","checklist":true}]},"todos":{"found":true,"items":[{"title":"Buy strings","details":"Buy strings for learning guitar.","sources":["example-note"]}]},"ideas":{"found":true,"items":[{"title":"Try watercolor painting","details":"Maybe try watercolor painting.","sources":["example-note"]}]}})json";
        const char * past_input = R"json({"date":"2026-01-02","entries":[{"id":"example-reading","text":"I finished my reading goal by reading a chapter today."}],"goals":[{"id":"reading","title":"Read a chapter","period":"daily","target":1,"unit":"times","checklist":true,"archived":false}],"confirmedActivities":[],"existingItems":[]})json";
        const char * past_output = R"json({"journal":"I finished my reading goal by reading a chapter today.","summary":"Read a chapter today.","captions":[],"goalActivity":[{"goalId":"reading","workedOn":true,"amount":1,"note":"Read a chapter.","sources":["example-reading"],"estimated":false}],"newGoals":{"found":false,"items":[]},"todos":{"found":false,"items":[]},"ideas":{"found":false,"items":[]}})json";
        const char * future_input = R"json({"date":"2026-01-03","entries":[{"id":"example-swimming","text":"I hope to swim next week. I did not swim today."}],"goals":[{"id":"swimming","title":"Go swimming","period":"weekly","target":1,"unit":"times","checklist":true,"archived":false}],"confirmedActivities":[],"existingItems":[]})json";
        const char * future_output = R"json({"journal":"I hope to swim next week, but did not swim today.","summary":"Swimming is a future plan.","captions":[],"goalActivity":[{"goalId":"swimming","workedOn":false,"amount":0,"note":"Only a future plan.","sources":[],"estimated":false}],"newGoals":{"found":false,"items":[]},"todos":{"found":false,"items":[]},"ideas":{"found":false,"items":[]}})json";
        llama_chat_message messages[] = {{"system", system.c_str()}, {"user", past_input}, {"assistant", past_output},
            {"user", future_input}, {"assistant", future_output}, {"user", example_input}, {"assistant", example_output}, {"user", user.c_str()}};
        int size = llama_chat_apply_template("chatml", messages, 8, true, nullptr, 0);
        if (size <= 0) throw std::runtime_error("Could not prepare the local review.");
        std::vector<char> formatted(size + 1);
        llama_chat_apply_template("chatml", messages, 8, true, formatted.data(), formatted.size());
        const auto vocab = llama_model_get_vocab(r.model);
        int n = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
        constexpr int context_size = 8192, output_limit = 2048, batch_size = 256;
        if (n <= 0 || n > context_size - output_limit) throw std::runtime_error("This day is too long for the on-device model. Use OpenAI or review a day with fewer words.");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, formatted.data(), size, tokens.data(), n, true, true) < 0) throw std::runtime_error("Could not read the local review input.");
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size; cp.n_batch = batch_size; cp.n_ubatch = 128;
        cpu_set_t allowed;
        int threads = 1;
        if (sched_getaffinity(0, sizeof(allowed), &allowed) == 0) threads = std::max(1, std::min(4, CPU_COUNT(&allowed)));
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.abort_callback = expired; cp.abort_callback_data = &control;
        r.context = llama_init_from_model(r.model, cp);
        if (!r.context) throw std::runtime_error("Not enough memory for the on-device model. Close other apps and try again.");
        r.batch = llama_batch_ext_init(r.context);
        if (!r.batch) throw std::runtime_error("Could not allocate the local model input.");
        r.sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!r.sampler) throw std::runtime_error("Could not prepare structured local output.");
        control.report("Reading notes", n, 0, started);
        for (int pos = 0; pos < n; pos += batch_size) {
            control.check();
            batch_tokens(r, tokens.data() + pos, std::min(batch_size, n - pos), pos);
            if (llama_process(r.context, LLAMA_PROCESS_TYPE_DECODE, r.batch)) { control.check(); throw std::runtime_error("Local model processing stopped. Retry with fewer words."); }
        }
        std::string output; bool complete = false; int generated = 0;
        for (int i = 0; i < output_limit; ++i) {
            control.check();
            llama_token token = sample(r, vocab);
            if (i % 8 == 0) control.report("Writing review", n, i, started);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            std::vector<char> piece(128);
            int count = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (count < 0) { piece.resize(-count); count = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (count < 0) throw std::runtime_error("Could not decode the local review.");
            output.append(piece.data(), count); generated++;
            batch_tokens(r, &token, 1, n + i);
            if (llama_process(r.context, LLAMA_PROCESS_TYPE_DECODE, r.batch)) { control.check(); throw std::runtime_error("Local output stopped. Your originals are preserved."); }
        }
        control.check();
        if (!complete) throw std::runtime_error("The on-device review exceeded its length limit. Try a shorter day or OpenAI.");
        control.report("Complete", n, generated, started);
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "On-device review failed. Your originals are saved.");
        return nullptr;
    }
}
