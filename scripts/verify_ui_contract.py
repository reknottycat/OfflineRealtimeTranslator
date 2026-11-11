#!/usr/bin/env python3
"""Static UI regression checks for the chat-style Android redesign.

Run from any working directory. Complements Gradle compilation/lint;
does not claim visual screenshot or on-device interaction verification.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
LAYOUTS = ROOT / "app/src/main/res/layout"
NS = "{http://schemas.android.com/apk/res/android}"
REQUIRED = {
    "activity_main.xml": {
        "btn_start_stop", "btn_pause_resume", "btn_settings", "btn_history",
        "btn_favorites", "scroll_view_russian", "scroll_view_chinese",
        "tv_russian_history", "tv_chinese_history", "tv_status",
        "tv_autosave_status", "tv_follow_ru", "tv_follow_zh",
        "btn_scroll_bottom_ru", "btn_scroll_bottom_zh",
        "tv_record_action", "tv_record_caption",
        "tv_asr_brand", "tv_asr_direction", "tv_asr_transcript_title",
    },
    "activity_settings.xml": {
        "btn_back", "radio_translation_engine", "rb_gemma4", "rb_lmt",
        "radio_inference_backend", "rb_backend_auto", "rb_backend_gpu",
        "rb_backend_cpu", "tv_backend_note",
        "radio_asr_model", "rb_asr_small", "rb_asr_large", "rb_asr_gigaam",
        "rb_asr_nemotron", "rb_asr_qwen", "spinner_asr_language",
        "tv_asr_language_label", "btn_download_asr_cpu",
        "spinner_asr_source", "tv_asr_hint", "et_asr_url", "tv_asr_status",
        "progress_asr", "btn_download_asr", "btn_pause_asr", "btn_import_asr",
        "btn_import_asr_folder", "btn_remove_asr",
        "tv_model_description", "spinner_model_source", "tv_source_hint",
        "et_model_url", "tv_model_status", "progress_model",
        "btn_download_model", "btn_pause_model", "btn_import_model",
        "btn_remove_model", "switch_drift_retry", "tv_version",
    },
    "activity_history.xml": {
        "btn_back", "tv_title", "spinner_time_range", "spinner_sort",
        "spinner_cleanup", "btn_multi_select", "bar_actions",
        "btn_action_delete", "btn_action_export", "btn_action_copy",
        "btn_action_favorite", "tv_selected_count", "cb_select_all",
        "recycler_view", "tv_empty", "tab_favorites", "tab_history",
    },
    "item_history.xml": {
        "btn_favorite", "tv_title", "tv_preview_zh", "tv_preview_ru",
        "cb_select",
    },
}

for filename, required in REQUIRED.items():
    root = ET.parse(LAYOUTS / filename).getroot()
    found = {}
    for el in root.iter():
        value = el.get(NS + "id", "")
        if value.startswith("@+id/"):
            key = value[5:]
            found[key] = found.get(key, 0) + 1
    missing = required - found.keys()
    duplicates = {key: count for key, count in found.items() if count != 1}
    assert not missing, f"{filename}: missing binding IDs: {sorted(missing)}"
    assert not duplicates, f"{filename}: duplicate binding IDs: {duplicates}"
    print(f"PASS {filename}: {len(required)} required IDs preserved, {len(found)} unique IDs")

main = ET.parse(LAYOUTS / "activity_main.xml").getroot()
for required_touch_target in ("btn_start_stop", "btn_pause_resume", "btn_settings",
                              "btn_history", "btn_favorites"):
    button = next(el for el in main.iter()
                  if el.get(NS + "id") == "@+id/" + required_touch_target)
    w = button.get(NS + "layout_width", "")
    h = button.get(NS + "layout_height", "")
    assert w.endswith("dp") and h.endswith("dp"), required_touch_target
    assert int(w.removesuffix("dp")) >= 48 and int(h.removesuffix("dp")) >= 48, (
        f"{required_touch_target} touch target below 48dp")
print("PASS primary recorder, navigation and pause touch targets: >=48dp")

print("UI binding contract PASS; screenshots/device accessibility still require QA")
