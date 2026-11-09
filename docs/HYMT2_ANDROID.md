# HY-MT2 third-engine integration (3.5-alpha-hymt2)

## Baseline and scope
Gemma 4 and LMT were merged together in PR #1, main commit
`7912d7921f59d7f894e1fc00787b11b7e1a3bf06`. PR #2 adds a **separate HY-MT2
profile**, not an LMT alias and not a rename of HY-MT1.5. The existing ordered
queue, Vosk, punctuation, history, favorites and engine ownership lease remain.

## Artifact and runtime contract
- Official repository: `tencent/Hy-MT2-1.8B-GGUF`.
- Revision: `a0c709d9fac510f2c807aa3af52872340dc37a4a`.
- File: `Hy-MT2-1.8B-Q4_K_M.gguf`.
- Exact size: **1,133,080,448 bytes**.
- SHA-256: `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`.
- GGUF architecture: `hunyuan-dense`; LMT continues to require `qwen3`.
- Same pinned llama.cpp `b30a5fdf370aa4cec02f6bbcc899fbbf3e77e763`;
  ARM64 **CPU only** for this profile, not Gemma's LiteRT GPU runtime.
- Tencent single-user template: BOS / HY User / translation instruction and
  source / HY Assistant. No LMT ChatML, no Gemma delimiters, no default system.
- Explicit BOS means tokenizer `add_special=false` for HY; LMT behavior unchanged.
- Greedy decoding, 2048 context, 512 generated-token limit. These are bounded
  deterministic mobile settings, not the upstream sampling benchmark settings.

Sources checked 2026-11-09:
https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/blob/main/Hy-MT2-1.8B-Q4_K_M.gguf
https://huggingface.co/tencent/Hy-MT2-1.8B/blob/main/chat_template.jinja
https://huggingface.co/tencent/Hy-MT2-1.8B/blob/main/config.json

## Use and boundaries
Settings → HY-MT2-1.8B → Download/Resume or Import → return to main → Start.
Gemma 4, LMT and HY-MT2 have separate files, checksum receipts and custom URLs.
Only one translation engine is resident. No model weights are included in APK.
The existing HF→HF Mirror fallback and custom HTTPS/ModelScope URL work for the
selected model. **No automatic, preverified ModelScope mirror is claimed.**
Vosk still needs its existing files; downloading HY alone does not install ASR.

HY subtitles are delivered **once per completed utterance**, not token by token.
This prevents reasoning blocks and partially validated money/currency text from
appearing as translated speech. Explicit complete think/answer wrappers are
processed; malformed wrappers fail visibly, not by guessing a translation.
The narrow ruble guard is retained with HY-specific warning text. It does not
validate every number, currency, negation, unit or commercial clause.

## What happened to the frontend?
The merged version really changed the header, dark card styling and settings /
model management. **The main layout is still separate Russian and Chinese
panes.** It is not a completed ChatGPT-style conversation-feed redesign.
This PR adds the third functional engine option and accurate CPU/model status;
it does not claim a full frontend reconstruction or a rendered-device UX audit.

## Verification
- Existing LMT prompt/contracts/real-weight smoke remain regression gates.
- Additional JVM tests cover HY routing, exact digest/size, per-model isolation,
  immutable mirror URLs, native profile IDs and rejection of unknown engines.
- Native contracts check the official prompt, UTF-8, token escaping, output
  filtering and distinct currency warnings.
- HY native smoke uses real SHA-verified weights with the same engine header as
  Android JNI: eight Russian inputs, incorrect-profile rejection, atomic final
  subtitles and a rejected post-cancellation call.
- Android unit tests, lint, APK native-library inventory and signature verification.

**Passing CI is not Android-device validation or broad translation-quality
acceptance.** Review raw/displayed outputs in the evidence. Actual microphone,
CPU latency, heat, memory pressure and repeated engine switching on a phone
remain device acceptance work. Existing Vosk/ONNX 16 KB alignment warnings remain.
Debug signing is runner-generated: do not uninstall an existing app without
exporting history and backing up app-private models; signed upgrade compatibility
must be checked against the APK certificate rather than assumed.
