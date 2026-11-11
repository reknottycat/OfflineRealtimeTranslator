# v4.3 Android ASR: manual language choice, China-first source policy, CPU budget

## User requirement and bug correction

Before v4.3, the app only had Russian Vosk/GigaAM ASR and a Russian→Chinese translation queue. Chinese or English speech was still interpreted as Russian and could be submitted to that Russian translation queue. **It was not a real automatic multilingual mode**.

The user now explicitly selects:
- ASR engine: Vosk Small, Vosk Large, GigaAM Russian, Qwen3-ASR-0.6B INT8, Nemotron 3.5 ASR Streaming 0.6B INT8 (560ms).
- **Speech language:** Russian, Chinese, English, or automatic (only Qwen/Nemotron). No automatic model switch.
- ASR hardware: Auto (bounded 2-core CPU), CPU, GPU experimental unavailable, NPU experimental unavailable. No unsupported delegate is claimed as running.

The routing policy only sends **Russian** recognized text to the existing Russian→Chinese translator. Chinese/English/unknown languages are stored as transcription without being passed through the wrong translation direction. Legacy Russian-only ASR cannot be selected with auto/Chinese/English; an actionable model selection error is displayed.

## Download sources and verified format identity

| Model | Official ModelScope checkpoint (research / provenance) | Actual Android ONNX runtime weights |
|--|--|--|
| Qwen3-ASR-0.6B | https://modelscope.cn/models/Qwen/Qwen3-ASR-0.6B | https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 (HF Mirror automatic access) |
| Nemotron 3.5 ASR Streaming 0.6B | https://modelscope.cn/models/nv-community/nemotron-3.5-asr-streaming-0.6b | https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11 (HF Mirror automatic access) |

**Critical safety invariant:** The original PyTorch/NeMo/safetensors checkpoints on ModelScope are *not* interchangeable with sherpa-onnx INT8 ONNX graphs. An unofficial FP32 ONNX on ModelScope is *not* automatically a byte-compatible INT8 ONNX file. Download order is: (1) a verified exact Android-compatible ModelScope file, if/when available; (2) China's `hf-mirror.com` for the converted graphs. **No exact matching INT8 bundle has been verified on ModelScope for these two checkpoints at the time of this PR**, so the first usable automatic source is HF Mirror. No `huggingface.co` direct or overseas GitHub proxy fallback is included. Optional custom domestic HTTPS base requires user action.

Expected exact file layout (total listed sizes; checked by installer):
- Qwen: `conv_frontend.onnx` 44,148,281 B, `encoder.int8.onnx` 182,491,662 B, `decoder.int8.onnx` 755,914,231 B, and tokenizer components 1,671,853 B / 2,776,833 B / 12,487 B. **Total 987,015,347 bytes**.
- Nemotron: `encoder.int8.onnx` 657,601,403 B, `decoder.int8.onnx` 14,978,075 B, `joiner.int8.onnx` 9,504,438 B, `tokens.txt` 131,440 B. **Total 682,215,356 bytes**.

Installer enforces HTTPS redirects, MIME rejection of HTML/JSON, Content-Range and exact file lengths, same-origin resume checkpoints, no original weight substitution, cancellation on leaving settings, and safe app-private installation. It has **not** pinned signed SHA-256 of every upstream converted ONNX binary. Therefore exact-size validation is not a cryptographic authenticity guarantee; native sherpa runtime initialization and real device QA are still mandatory.

**Sources:**
- Qwen: https://modelscope.cn/models/Qwen/Qwen3-ASR-0.6B and https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html
- Nemotron: https://modelscope.cn/models/nv-community/nemotron-3.5-asr-streaming-0.6b and https://k2-fsa.github.io/sherpa/onnx/nemo/nemotron-streaming.html
- Qwen ONNX source: https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/tree/master/model_0.6B (FP32, cannot silently use in place of INT8)
- Actual Android inference is sherpa-onnx 2.0.1 official JNI/AAR from the existing v4 integration. Real phone startup for Qwen and Nemotron **not yet accepted**.

## Performance and hardware gates

- Current runtime: CPU, **2 worker threads** for Qwen/GigaAM/Nemotron. Do not load 2 ASR models concurrently; one ASR engine selected at startup. Translation model is loaded separately and may compete for CPU/RAM (Gemma GPU, LMT/HYMT CPU).
- Qualcomm Snapdragon 8 Elite class: Android Qualcomm QNN Execution Provider + AI Runtime SDK is a candidate path for Hexagon/HTP, **but only** if the required operators and quantized model subgraphs are supported and runtime is actually bundled. https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- MediaTek Dimensity 9400 class: NeuroPilot / vendor-specific LiteRT delegate or ONNX EP is a *conditional* route requiring compatible models and supported vendor SDK; Android consumer hardware alone does not authorize third-party NPU access. https://genio.mediatek.com/doc/iot-aihub/ai_hub/supported_os/android.html
- GPU/NPU selectors deliberately return `unsupported` rather than falling back without notice. Enable them only after implementing the EP and recording on-device provider profiling traces.

### Minimum physical-device QA
Compare 60 annotated utterances per language on the same phone, compute WER/CER, p50/p95 first and final transcript latency, 10–30-minute peak PSS/RSS, CPU load, thermal throttling, battery, steady-state audio dropouts, and performance while a translation engine is running. Test at least one Xiaomi Snapdragon 8 Elite and one Dimensity 9400+ device. New models and ASR modes remain Draft until CI and real-device tests. Build-only success does NOT demonstrate correct detection or NPU deployment.
