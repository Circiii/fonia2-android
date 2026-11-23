// The C++ face of the Whisper runtime, used by the JNI bridge. Everything here runs on one thread at a time
// (WhisperCppRuntime confines a model to a single-thread dispatcher), and the decode step allocates nothing:
// the staging buffers below are sized once, at load.
#pragma once

#include <cstddef>

struct fonia_whisper;

// Loads a ggml Whisper model on the CPU backend. `heads` holds the alignment heads as (layer, head) pairs,
// `n_heads` pairs of them, from generation_config.json; with none, decode steps carry no alignment row.
// Returns null and writes a message into `error` on failure.
fonia_whisper * fonia_whisper_load(const char * model_path, int n_threads, const int * heads, int n_heads, char * error, size_t error_size);

void fonia_whisper_free(fonia_whisper * fw);

int fonia_whisper_n_vocab(const fonia_whisper * fw);
int fonia_whisper_n_audio_ctx(const fonia_whisper * fw);
int fonia_whisper_n_text_ctx(const fonia_whisper * fw);

// WhisperKit's featureExtractor.windowSamples: 30 s at 16 kHz.
int fonia_whisper_window_samples(const fonia_whisper * fw);

bool fonia_whisper_has_alignment(const fonia_whisper * fw);

// The staging buffer encode reads: fonia_whisper_window_samples() floats.
float * fonia_whisper_samples(fonia_whisper * fw);

// Log-mel spectrogram and audio encoder over the first n_samples staged samples. 0 on success.
int fonia_whisper_encode(fonia_whisper * fw, int n_samples);

// One decoder step: `token` at cache position `n_past`. Cache cells at or past n_past are dropped first,
// so the step sees exactly the tokens fed at positions 0 until n_past. 0 on success; then
// fonia_whisper_logits() holds the next-token logits and fonia_whisper_alignment() the step's
// cross-attention row over all encoder positions, averaged over the alignment heads.
int fonia_whisper_decode(fonia_whisper * fw, int token, int n_past);

const float * fonia_whisper_logits(const fonia_whisper * fw);
const float * fonia_whisper_alignment(const fonia_whisper * fw);

const char * fonia_whisper_system_info();
