# Russian ASR choice + subtitle UX (2026-11)

## Vosk model comparison (publisher benchmark, **not** phone A/B test)

Official: https://alphacephei.com/vosk/models

| Russian model | ZIP | OpenSTT audiobooks WER | OpenSTT YouTube WER | SOVA Devices WER | Expected target |
| -- | --: | --: | --: | --: | -- |
| Small 0.22 | ~45 MB | 22.71% | 31.97% | 29.89% | Mobile, ~300 MB runtime RAM |
| Large 0.42 | ~1.8 GB | 11.1% | 19.5% | 17.9% | Server, potentially up to 16 GB runtime RAM |

A **lower** WER is better. The Large 0.42 *version identifier* is newer than Small 0.22, and publisher benchmarks favor Large; however, the two are different model sizes, so this is not a controlled model age comparison. No reliable model training end date was established. **Do not** automatically select Large as the default on Android. Small stays default until device A/B validation; offer Large only as explicit high-memory choice.

## Alternative Russian models, deployability matters

1. Alibaba DAMO / iic **UniASR Russian 2-pass 95M**, ModelScope: https://modelscope.cn/models/iic/speech_UniASR_asr_2pass-ru-16k-common-vocab1664-tensorflow1-offline. Model repo ~425.97 MB, TF1. ModelScope model card explicitly says official implementation operates **only on Linux x86-64**; no supported on-device Android inference stack or verified TFLite/ONNX export was established. It is **not** an already working third Android ASR engine. Avoid listing it as installed, offering download into a Vosk runtime, or advertising false accuracy.
2. **GigaAM v2 Russian** via sherpa-onnx: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html . The real Android demo and the int8 transducer model are available; model payload contains ~226 MB encoder + 3.2 MB decoder + 1.4 MB joiner (~231 MB total). **This is not an Alibaba model**; it is a third candidate for actual native mobile evaluation. A separate sherpa-onnx runtime adapter, verified download/archive/install, and microphone/ASR callbacks are required.
3. SenseVoice-Small/flagship Fun-ASR-Nano are *not* interchangeable with Russian UniASR. Do not assume the standard Fun-ASR-Nano Chinese/English/Japanese checkpoint recognizes Russian. The MLT checkpoint is substantially larger (800M) and its explicit language list differs; check the exact card before selection.

## Phone acceptance gates before changing default

Use the **same** 60-utterance Russian test set (natural conversation; valves/contract/port industry terms; noise/phone speech), with verbatim annotated ground truth. On the **same** Android device, compare:
- WER, CER, industry vocabulary, false insertion/deletion, punctuation / segmentation;
- first partial latency, final sentence latency, real-time factor (RTF);
- peak PSS/RSS, warm startup and repeated-session stability, battery drain/thermal over at least 30 minutes;
- long-duration transcription (10+ min), interruption/resume, background/foreground, and crash recovery.

Choose the smallest model meeting quality, privacy and performance thresholds. Version number and checkpoint file size alone do **not** select a winner.

## Mature subtitle interaction references

- Google Pixel Recorder: saving recordings/transcriptions at the end of a capture, browsing/searching, explicit history: https://support.google.com/pixelphone/answer/16267367
- Otter transcript: timestamped, conversation-based notes with dedicated transcript view and controls: https://help.otter.ai/hc/en-us/articles/5093228433687-Conversation-Page-Overview
- Google Live Transcribe: scrolling up to find earlier transcript text and explicit transcript history controls: https://support.google.com/pixelphone/answer/9158064

Implemented in v3.8:
- Russian *final* ASR segments immediately enter Room history; Chinese translation completion updates the same row. RU-only entries persist even if translation is still running.
- Stop/pause/settings/Activity destruction trigger a snapshot; queued writes drain on a dedicated IO coroutine, no blocking database call on UI thread.
- A user-initiated upward scroll pauses auto-follow; explicit follow button and jump-to-bottom restore it. Programmatic text redraw/streaming no longer erroneously turns follow off.
- Two panes have clear language labels and live-follow state; one status line reports auto-save attempts/success/failure.
- No claim that raw audio is saved. The feature stores recognized **text only**; to save sound, recording and permission UX would need separate work.

Limitations: no device/OS process-kill stress test; Room async writes cannot guarantee that a just-spoken but **non-finalized** ASR partial is durable before a hard kill. UI and historical record reflect finalized segments, not every token.
