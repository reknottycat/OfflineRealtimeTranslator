# GigaAM v2 Russian ASR — v4.0 Android integration

## Scope

This **third-party** Russian ASR is distinct from Alibaba UniASR and from Vosk.

The speech engine now supports three actual choices:

| Engine | Model | Download bytes | Android runtime | Notes |
| --- | --- | ---: | --- | --- |
| Vosk Small 0.22 | `vosk-model-small-ru-0.22.zip` | 46,236,750 | Vosk JNI | Lightweight baseline |
| Vosk Large 0.42 | `vosk-model-ru-0.42.zip` | 1,837,429,010 | Vosk JNI | ~3.5–3.7GB unzipped; high RAM |
| GigaAM v2 Russian CTC INT8 | `model.int8.onnx` | 236,457,977 | sherpa-onnx 2.0.1 AAR JNI, CPU | Third-party speech recognition |

Only the engine selected in Settings loads when MainActivity starts. Translation models Gemma 4, LMT and HY-MT2 remain separate and are not replaced.

## Exact pinned model source

- Repository: [csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19)
- Immutable HF revision: `499bb8aa0334ec8399ff827f00d791dc0ac7015d`
- File: `model.int8.onnx`
- Exact bytes: `236457977`
- SHA-256: `d0ce4aef25f58d495781ee8f05320d9e51b821f47804e07aa6549b53a72f67e8`
- Russian CTC `tokens.txt` (34 symbols) is bundled with the application and copied to the model directory at first load.
- Preferred model download: pinned HTTPS URL on `hf-mirror.com`, followed by `huggingface.co`. A matching exact ModelScope repository was not verified, so it is **not** invented. Custom direct HTTPS links allowed only with the same SHA-256.

## Native runtime supply

The official sherpa-onnx Android AAR is *not* checked into this public Git repository. The build job downloads
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar`
using `python3 scripts/setup_sherpa_aar.py` and verifies SHA-256
`a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`.
Kotlin uses `com.k2fsa.sherpa.onnx.OfflineRecognizer` with a NeMo CTC model path; JNI library is packed in the ARM64 APK.

## Real-time behavior and limitations

This checkpoint is an **offline CTC recognizer**, not a true streaming ASR model. To create live-looking subtitles, Android records 16kHz mono PCM samples, buffers speech with a simple RMS threshold,
decodes interim utterance windows approximately every 3 seconds, and commits a finalized Russian sentence when it detects ~0.8s silence or reaches 12 seconds. It forwards the final to the original translation queue and the v3.8 Room subtitle-autosave implementation. Only finalized subtitles are durable. This is a preliminary voice activity detector, not a trained VAD; expect mis-segmentation under noise.

To prevent losing queued final speech, new recording and settings navigation are blocked until pending GigaAM decodes drain. Android process kills, OOM and unpredictable audio interruptions still require device validation.

## Install and reproduce

1. Install the **v4 test APK** (Android ARM64) separately, without removing any older app; the debug package ID is `com.bohanli.ruzhtranslator.preview.gigaam`.
2. Settings → Russian ASR → GigaAM v2 Russian INT8 → Download/Continue (or Import GigaAM ONNX). The app SHA-checks the exact model before activating.
3. Independently install the desired translation model (Gemma 4, LMT, or HY-MT2).
4. Return to main screen; start listening. Russian recognized text displays above, Chinese translations below; finalized segments auto-save in Room history.
5. To switch back to Vosk Small/Large, select the corresponding radio option; model selection reloads on returning home.

**Not established by compiling an APK**: successful model load/inference on a physical phone, real download speed on a Chinese mobile network, Russian WER relative to Vosk, latency, battery/heat over 30+ minutes, Android shared-library conflicts on different chipsets. A true on-device qualification requires permissioned device QA. Do not claim these tests passed.

## Acceptance protocol

Record **the same** controlled 60 Russian utterances covering colloquial speech, commercial numbers/currencies, industry jargon, background noise and accents. Measure WER/CER, segmentation errors, first- and final-caption latency, real-time factor, p95 RAM, crash/ANR rate and temperature. A 30-minute continuous recording checks resource stability and automatic history persistence. Change default from Vosk only when evidence supports that switch.
