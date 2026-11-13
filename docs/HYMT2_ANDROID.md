# HY-MT2 Android integration (3.5 alpha)

Baseline: PR #1 merged to `main` as `7912d7921f59d7f894e1fc00787b11b7e1a3bf06` on 2026-11-09 at the user's explicit request. Gemma 4 and LMT were both in that one PR; there was no second outstanding engine PR to merge.

## Third engine, not an alias

- Tencent HY-MT2-1.8B official Q4_K_M GGUF; CPU only in this version.
- Reuses the already pinned llama.cpp library and JNI byte transport, but has a distinct native profile ID, Hunyuan architecture check and Hunyuan chat template.
- Explicit Hunyuan BOS is tokenized without auto-adding a duplicate BOS. No Gemma template, no LMT ChatML, no added thinking prefix.
- 2048-token runtime context, 512-token output cap, deterministic greedy sampling for this mobile baseline. These are application limits, not claims about the model's maximum context or official benchmark sampling.
- Existing single-owner operation mutex/runtime lease, cancellation, UTF-8 streaming, history, Vosk and punctuation flow remain in place.
- Ruble-sensitive output is buffered until the existing narrow currency guard checks it; the HY-MT2 label is distinct. This is not complete numeric or translation-quality validation.

## Pinned weights

- Repository: `tencent/Hy-MT2-1.8B-GGUF`
- Revision: `a0c709d9fac510f2c807aa3af52872340dc37a4a`
- File: `Hy-MT2-1.8B-Q4_K_M.gguf`
- Bytes: `1133080448`
- SHA-256: `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`
- License indicated by upstream: Apache-2.0. Weights are downloaded separately, not bundled in this APK.

Sources checked on 2026-11-09:
- https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/blob/main/Hy-MT2-1.8B-Q4_K_M.gguf
- https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/raw/main/Hy-MT2-1.8B-Q4_K_M.gguf
- https://huggingface.co/tencent/Hy-MT2-1.8B/blob/main/chat_template.jinja
- https://github.com/Tencent-Hunyuan/Hy-MT2

## User flow

Settings → 腾讯 HY-MT2-1.8B → download/resume or import → return to main screen → explicitly start listening.

The v3.6 downloader now uses verified ModelScope files first, then HF Mirror, user-requested proxy candidates, and Hugging Face. The ModelScope namespace is **Tencent-Hunyuan**, not the HF namespace `tencent`; its own pinned revision is `ef1d40b8b315575d30d1eb6579996130b8fc0bb2`. The artifact has the exact size and SHA-256 listed above. See [source evidence](model-downloads/README.md) and [chunked download behavior](CHINA_MODEL_DOWNLOADS.md). Each engine has separate files and saved custom URL; removing one does not remove the others. Speech models are still provisioned separately.

## Frontend status

The previous PR did change the frontend: main toolbar, neutral dark surfaces, rounded source/translation panels, engine/hardware settings, download/import/remove controls. It did **not** implement a complete ChatGPT-style chat timeline or a full app redesign. The source/translation panes still retain the original split layout and color matching. This PR adds a functioning third engine radio option and truthful CPU-only notes; it does not claim a full UI rewrite.

## Acceptance boundaries

CI must pass Android unit tests/lint/APK, unchanged LMT real-weight regression, and HY-MT2 real-weight host inference. HY tests cover six Russian segments twice, UTF-8 stream/final equality, context reset, cancellation before/in a call and rejection of HY weights under the LMT profile.

Android phone inference, downloaded-model lifecycle, mic/subtitle end-to-end, thermal/latency measurements and broad translation quality remain separate manual acceptance gates. GPU/NPU/STQ1.25-bit support is not part of this PR. Keep this PR unmerged until reviewed; previous main merge does not authorize automatic merge of new HY work.
