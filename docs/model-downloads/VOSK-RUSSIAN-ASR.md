# Vosk Russian ASR: Android in-app model manager (v3.7 preview)

The ASR switch was formerly only a radio selector for models provisioned manually. This release adds download / resume / ZIP import / uninstall UI for both existing IDs.

| Setting | Vosk model | Exact ZIP size | Authentication | Download sources |
| --- | --- | ---: | --- | --- |
| Small (recommended on phone) | vosk-model-small-ru-0.22 | 46,236,750 bytes | SHA-256 `961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4` | HF Mirror via pinned rhasspy HF commit; HF original; Alphacephei official; independently matching GitHub release |
| Large (server-class) | vosk-model-ru-0.42 | 1,837,429,010 bytes | **No confirmed publisher SHA-256**: HTTPS, expected length, per-entry ZIP CRC and Vosk structure only | Alphacephei official or custom HTTPS ZIP URL |

Sources: [official Vosk models](https://alphacephei.com/vosk/models), [pinned Small HF file](https://huggingface.co/rhasspy/vosk-models/blob/e7ac2109d134b5f2404ba95389b2fb51916d4cab/ru/vosk-model-small-ru-0.22.zip), [independent Small release](https://github.com/BartekReterski/VoskModels/releases/tag/v1).

**ModelScope:** No verified exact Russian Small/Large ZIP repository was found. The UI must not advertise fabricated ModelScope models. A user with a trusted HTTPS ZIP mirror can enter its direct URL. Chinese access via HF Mirror is only known for the Small archive, not Large.

Vosk states large models are primarily intended for servers and can use up to 16 GB of memory in use; they may be unsuitable on Android even if downloaded successfully.

## Operation
Open settings > Vosk Russian ASR > select Small or Large > download/continue. During downloads a progress bar, cancel/resume, and error state are shown. Full archive remains in a separate app-private downloads folder until validated and safely extracted. Model switching restarts ASR on returning home. It never implicitly downloads Large. A manually installed model remains discoverable as before, but downloaded app-private models take precedence.

Installer safeguards: HTTPS-only origins and redirects; exact byte length / Content-Range; resumable partials; disk space checks; Zip-Slip protection; duplicate paths and zip-bomb limits; CRC for every extracted entry; checks `am/final.mdl`, `conf/mfcc.conf`, and at least one `graph/*.fst` (large graph may use HCLG rather than Gr/HCLr); atomic activation with rollback; no deletion of shared storage or history. Uninstall only removes app-private Vosk model files.

## Limitations
- The Large model's final model file contents are **not authenticated against a pinned publisher digest** (only ZIP integrity and HTTPS). Avoid claiming full authenticity.
- The Large model has not been verified on any Android handset; possible OOM or poor throughput.
- Download requests are launched while Settings is open. Reopen and press Continue after leaving or rotating; the partial archive remains available.
- Current transport is **sequential HTTP Range** (not parallel chunks); it will rotate through valid origins on errors.
- APK and CI validation do not imply on-device voice recognition performance or quality acceptance.
