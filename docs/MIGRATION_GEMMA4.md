# Gemma 4 E2B + LiteRT-LM migration

This is a **first-stage migration** on a feature branch. It preserves Vosk Russian ASR,
Recasepunc, TranslationQueue, history, pause/resume and the existing Room schema.

## Current scope

- Native llama.cpp/CMake/OpenCL stub has been removed from **the Gradle build**, while
  its source remains in the repository for historical reference.
- LiteRT-LM Kotlin 0.16.1 is pinned for compatibility with AGP 9.1 built-in Kotlin 2.2.
- A single-thread owner manages Engine and Conversation; every segment creates a
  short conversation and keeps the model loaded. GPU can fall back to CPU.
- App-private model installer supports resumable HTTPS, local file import,
  strict file size and SHA-256 verification.
- Sources: official Hugging Face, third-party HF mirror and custom ModelScope HTTPS
  file URL. No unverified ModelScope repository URL is hardcoded.
- Models are **not** included in the APK. Network is only needed for downloading.

## Pinned model

- Repository: `litert-community/gemma-4-E2B-it-litert-lm`
- File: `gemma-4-E2B-it.litertlm`
- Revision: `9262660a1676eed6d0c477ab1a86344430854664`
- Expected bytes: `2588147712`
- SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`

## Validation gates

1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
2. Install the GitHub Actions debug APK on an arm64 Android 10+ device.
3. Download the E2B model over an actual network connection; interrupt and resume.
4. Confirm file SHA-256, offline start, GPU inference and CPU fallback.
5. Confirm the Russian→Chinese stream order and pause/resume on live microphone.
6. Run a 30-minute thermal/latency test; do not reuse Gemma 3 benchmarks.
7. Check Android 13+ notifications/long-running download behavior.

## Deferred

LMT is **not** enabled in this PR. Its runtime and model format must be confirmed
before implementing an executable second backend. The settings screen shows a
disabled LMT choice rather than pretending translation used it.

Note that GitHub Actions verifies compilation and unit tests, not on-device
inference correctness. No model files are downloaded by CI.
