# Stage 2: NiuTrans LMT on Android

This change enables a real second translation backend, not an alias for Gemma.

## Runtime and weights

- Original checkpoint: NiuTrans/LMT-60-1.7B, a Qwen3-based multilingual translation model.
- Quantized artifact: **community** conversion by mradermacher, `LMT-60-1.7B.Q4_K_M.gguf` (about 1.28 GB).
- Immutable revision: `f03d51b43463d8a457eee1cd765220de69e9bac8`.
- SHA-256: `54c6244f100330264cef607188413ee82cb8a254f0cf950011aaa9cee74f7eb5`.
- Runtime: repository-pinned llama.cpp `b30a5fdf370aa4cec02f6bbcc899fbbf3e77e763`, ARM64 CPU, 4 threads, 2048-token context, at most 512 output tokens. CPU remains optimized in debug APKs.
- Native inference is built separately from LiteRT-LM, with static symbols hidden.
- JNI transports UTF-8 byte arrays; a streaming decoder handles split Chinese, Russian and supplementary characters.
- Uses the NiuTrans translation instruction and checkpoint ChatML template, not Gemma formatting. This mobile implementation uses greedy decoding rather than the authors' 5-beam example; paper scores are not claimed.

## Switch and model management

Choose Gemma 4 or LMT in Settings. Downloads, imports, deletion, hashes and directories follow that selection. Settings stop the old session; returning recreates it. A process-wide engine lease prevents both translation weight sets from being resident together. Opening or reloading does not automatically enable the microphone.

Gemma's GPU/CPU preference is retained independently. **LMT in this build is CPU only**; the UI must not claim GPU acceleration for it.

Hugging Face and HF Mirror point to the same pinned file. Automatic mode retries the second source after failure. A ModelScope/custom HTTPS file URL is accepted only if its complete digest matches the selected artifact. No preverified ModelScope repository has been found or invented. All redirects must remain HTTPS; HTTP Range offsets are checked. Imports are verified before atomic replacement, and never delete the other model.

Downloads are foreground screen-scoped operations. Leaving/rotating the settings screen cancels work; use Download again to resume. A foreground service/background download manager is not implemented.

## Validation boundary

CI builds the ARM64 APK, runs JVM unit tests and lint, checks both native libraries and APK signature, and uploads reports. A separate Linux job downloads the actual pinned LMT model and uses **the same C++ core as JNI** for two Russian-to-Chinese translations and a cancellation assertion.

The Linux test is not an Android device test, a translation-quality benchmark, or a mobile thermal/latency measurement. Vosk ASR files still need to be provisioned separately. Keep PR #1 as a draft until the phone microphone-to-subtitle path and repeated switching have been exercised.

## Primary sources

- https://github.com/NiuTrans/LMT
- https://huggingface.co/NiuTrans/LMT-60-1.7B/blob/main/chat_template.jinja
- https://huggingface.co/mradermacher/LMT-60-1.7B-GGUF/blob/main/LMT-60-1.7B.Q4_K_M.gguf
- https://github.com/ggml-org/llama.cpp/tree/b30a5fdf370aa4cec02f6bbcc899fbbf3e77e763

Existing `MIGRATION_GEMMA4.md` describes the earlier first-stage snapshot; its statement that LMT is disabled is superseded by this document, subject to the validation boundary above.
