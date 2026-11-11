# v4.2 — ChatGPT-inspired offline interpreter UI

## Design intent
A quiet, legible, dark conversation workspace designed for live Russian speech + Simplified Chinese translation. Inspired by ChatGPT's restrained typography, neutral surfaces, generous touch targets, and anchored primary composer action; no ChatGPT branding/assets or source code are copied.

## Screens
- **Live**: 68dp header with History/Settings, one always-visible status/autosave surface, source (Russian) and target (Chinese) transcript cards with independent live-follow controls, a bottom composer dock with one 60dp Start/Stop action, explicit recording state labels, Pause when listening and a Favorites shortcut.
- **Settings**: grouped in usage order **ASR → translation engine → translation model downloads → preferences**. Maintains all existing ViewBinding IDs used by SettingsActivity and the programmatically inserted HY-MT2 option. Primary buttons green, secondary neutral; selected radio states remain unmistakable.
- **History**: roomy 96dp cards, tighter neutral type hierarchy, larger toolbar/tab targets and no bright competing text colors.

## Visual tokens
| Token | Value |
|--|--|
| Background | #141414 |
| Surface | #222323 |
| Secondary surface | #2B2C2D |
| Stroke | #363737 |
| Foreground | #F4F4F4 |
| Muted | #A4A5A6 |
| Accent | #19C59A (sparse use) |
| Subtitle segments | four restrained near-neutral tones instead of a 16-color rainbow |
| Corners | 12/17/20/23dp scale |
| Tap targets | >=48dp for home main controls |
| Transcript | source 16sp; target 17sp; generous line spacing |

## Behavior compatibility
MainActivity retains all existing IDs for subtitle updates, on-demand GigaAM/Vosk initialization, translation engines, history, favorites, auto-follow, and autosave. It additionally updates **tv_record_action** and **tv_record_caption** with Start/Listen/Pause/Model-error/ASR-only states. Status errors remain visible (up to 4 lines) and the Start button remains actionable as in v4.1.

## Test boundaries
- `python3 scripts/verify_ui_contract.py`: parses all four XML layouts and checks unique required ViewBinding IDs and minimum 48dp key touch targets.
- Android JVM tests / lint / APK build / signing / native LMT/HY-MT2 regression workflows.
- **Not yet visually verified on phone:** screen density 320–640dpi, very small phones, font scaling 200%, insets/gesture bar, color contrast measured in a simulator, actual recording tap behavior, memory pressure during ASR. No external full visual redesign engine was available; code-level QA is not pixel-perfect screenshot acceptance.
- Preview APK has package ID `com.bohanli.ruzhtranslator.preview.gigaam.chatstyle`, distinct from v4.1 due to temporary debug-signing keys. Prior data is preserved in the older installed app but is not automatically shared to the new preview.
