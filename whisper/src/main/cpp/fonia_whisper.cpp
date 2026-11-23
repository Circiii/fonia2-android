// Unity translation unit: the pinned whisper.cpp v1.9.5 source, compiled unchanged, plus the few functions
// the Fonia runtime needs that whisper.h does not export.
//
// WhisperKit's TextDecoder returns, with every step's logits, the cross-attention of that step averaged
// over the model's alignment heads (`alignment_heads_weights`, 1 x 1500), and TokenTiming runs its DTW
// over those rows. whisper.cpp computes the same softmaxed cross-attention for its experimental DTW
// timestamps, but the public whisper_decode() never keeps it (save_alignment_heads_QKs = false), and
// whisper_decode_internal() is static. Including the source here gives fonia_whisper_decode() access to
// it without touching the submodule: it is whisper_decode_with_state() with that one flag set, followed
// by a read of the tensor the decoder graph already produced.

#include "whisper.cpp/src/whisper.cpp"

#include "fonia_whisper.h"
#include "ggml-cpu.h"

#include <cstdio>

struct fonia_whisper {
    whisper_context * ctx = nullptr;
    ggml_threadpool * pool = nullptr;
    int n_threads = 1;

    std::vector<whisper_ahead> heads;   // must outlive ctx: whisper keeps the pointer in its params
    std::vector<float> samples;         // staged audio, one window
    std::vector<float> qk;              // alignment heads x audio positions, for one step
    std::vector<float> alignment;       // their mean over heads

    int n_vocab = 0;
    int n_audio_ctx = 0;
    int n_text_ctx = 0;
};

fonia_whisper * fonia_whisper_load(const char * model_path, int n_threads, const int * heads, int n_heads, char * error, size_t error_size) {
    auto * fw = new fonia_whisper();
    fw->n_threads = std::max(1, n_threads);
    for (int i = 0; i < n_heads; ++i) {
        fw->heads.push_back(whisper_ahead { heads[2 * i], heads[2 * i + 1] });
    }

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    // The cross-attention weights exist only on the non-flash path, and whisper.cpp turns DTW off
    // when flash attention is on.
    params.flash_attn = false;
    params.dtw_token_timestamps = !fw->heads.empty();
    params.dtw_aheads_preset = fw->heads.empty() ? WHISPER_AHEADS_NONE : WHISPER_AHEADS_CUSTOM;
    params.dtw_aheads.n_heads = fw->heads.size();
    params.dtw_aheads.heads = fw->heads.empty() ? nullptr : fw->heads.data();

    fw->ctx = whisper_init_from_file_with_params(model_path, params);
    if (fw->ctx == nullptr || fw->ctx->state == nullptr) {
        snprintf(error, error_size, "whisper.cpp could not load %s", model_path);
        fonia_whisper_free(fw);
        return nullptr;
    }

    fw->n_vocab = whisper_n_vocab(fw->ctx);
    fw->n_audio_ctx = whisper_n_audio_ctx(fw->ctx);
    fw->n_text_ctx = whisper_n_text_ctx(fw->ctx);
    fw->samples.assign(WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE, 0.0f);
    fw->qk.assign((size_t) fw->n_audio_ctx * std::max<size_t>(1, fw->heads.size()), 0.0f);
    fw->alignment.assign(fw->n_audio_ctx, 0.0f);

    // One thread pool for the model's lifetime. Without it ggml creates and joins its worker threads on
    // every graph, which is every decoder step.
    ggml_threadpool_params pool_params = ggml_threadpool_params_default(fw->n_threads);
    fw->pool = ggml_threadpool_new(&pool_params);
    if (fw->pool != nullptr) {
        for (ggml_backend_t backend : fw->ctx->state->backends) {
            if (ggml_backend_is_cpu(backend)) {
                ggml_backend_cpu_set_threadpool(backend, fw->pool);
            }
        }
    }
    return fw;
}

void fonia_whisper_free(fonia_whisper * fw) {
    if (fw == nullptr) {
        return;
    }
    if (fw->ctx != nullptr) {
        whisper_free(fw->ctx);
    }
    if (fw->pool != nullptr) {
        ggml_threadpool_free(fw->pool);
    }
    delete fw;
}

int fonia_whisper_n_vocab(const fonia_whisper * fw) { return fw->n_vocab; }

int fonia_whisper_n_audio_ctx(const fonia_whisper * fw) { return fw->n_audio_ctx; }

int fonia_whisper_n_text_ctx(const fonia_whisper * fw) { return fw->n_text_ctx; }

int fonia_whisper_window_samples(const fonia_whisper * fw) { return (int) fw->samples.size(); }

bool fonia_whisper_has_alignment(const fonia_whisper * fw) { return !fw->heads.empty(); }

float * fonia_whisper_samples(fonia_whisper * fw) { return fw->samples.data(); }

int fonia_whisper_encode(fonia_whisper * fw, int n_samples) {
    if (n_samples <= 0 || n_samples > (int) fw->samples.size()) {
        return 1;
    }
    whisper_state * state = fw->ctx->state;
    if (whisper_pcm_to_mel_with_state(fw->ctx, state, fw->samples.data(), n_samples, fw->n_threads) != 0) {
        return 2;
    }
    if (whisper_encode_with_state(fw->ctx, state, 0, fw->n_threads) != 0) {
        return 3;
    }
    return 0;
}

int fonia_whisper_decode(fonia_whisper * fw, int token, int n_past) {
    whisper_context * ctx = fw->ctx;
    whisper_state * state = ctx->state;
    if (n_past < 0 || n_past >= fw->n_text_ctx || token < 0 || token >= fw->n_vocab) {
        return 1;
    }

    // whisper_decode_with_state(), keeping the alignment heads' cross-attention.
    const whisper_token tokens[1] = { token };
    whisper_batch_prep_legacy(state->batch, tokens, 1, n_past, 0);
    whisper_kv_cache_seq_rm(state->kv_self, 0, n_past, -1);
    const bool keep = !fw->heads.empty();
    if (!whisper_decode_internal(*ctx, *state, state->batch, fw->n_threads, keep, nullptr, nullptr)) {
        return 2;
    }

    if (keep) {
        // [n_tokens = 1, n_audio_ctx, n_heads], F32 and contiguous: the transposed concat of the
        // softmaxed K*Q of every alignment head, as whisper.cpp's own DTW reads it.
        const ggml_tensor * qk = state->aheads_cross_QKs;
        if (qk == nullptr || qk->type != GGML_TYPE_F32 || !ggml_is_contiguous(qk) ||
            qk->ne[0] != 1 || qk->ne[1] != fw->n_audio_ctx || qk->ne[2] != (int64_t) fw->heads.size()) {
            return 3;
        }
        ggml_backend_tensor_get(qk, fw->qk.data(), 0, ggml_nbytes(qk));
        const int n_audio = fw->n_audio_ctx;
        const int n_heads = (int) fw->heads.size();
        const float scale = 1.0f / (float) n_heads;
        for (int a = 0; a < n_audio; ++a) {
            float sum = 0.0f;
            for (int h = 0; h < n_heads; ++h) {
                sum += fw->qk[(size_t) h * n_audio + a];
            }
            fw->alignment[a] = sum * scale;
        }
    }
    return 0;
}

const float * fonia_whisper_logits(const fonia_whisper * fw) { return whisper_get_logits_from_state(fw->ctx->state); }

const float * fonia_whisper_alignment(const fonia_whisper * fw) { return fw->alignment.data(); }

const char * fonia_whisper_system_info() { return whisper_print_system_info(); }
