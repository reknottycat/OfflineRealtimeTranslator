#!/usr/bin/env python3
"""Validate build evidence; this does not claim phone inference or model quality."""
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

reports = sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('No unit test reports were generated')
totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for report in reports:
    root = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(root.attrib.get(key, 0))
if totals['failures'] or totals['errors'] or totals['skipped']:
    raise SystemExit(f'Unit tests must pass without skipping: {totals}')
if totals['tests'] < 25:
    raise SystemExit(f'Missing baseline/HY-MT2 unit tests: {totals}')
apk = Path('dist/OfflineRealtimeTranslator-Gemma4-LMT-HYMT2-debug.apk')
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    required = ['lib/arm64-v8a/liblmt_translator.so', 'lib/arm64-v8a/liblitertlm_jni.so']
    for library in required:
        if library not in names:
            raise SystemExit(f'Missing backend: {library}')
    if any(name.endswith(('.gguf', '.litertlm')) for name in names):
        raise SystemExit('Model weights must not be embedded in the APK')
lint_report = Path('app/build/reports/lint-results-debug.xml')
if not lint_report.is_file():
    raise SystemExit('No machine-readable lint report found')
issues = ET.parse(lint_report).getroot().findall('issue')
lint_errors = sum(issue.get('severity') in ('Error', 'Fatal') for issue in issues)
lint_warnings = sum(issue.get('severity') == 'Warning' for issue in issues)
if lint_errors:
    raise SystemExit(f'Lint errors remain: {lint_errors}')
result = {
    'unit_tests': totals,
    'lint_errors': lint_errors,
    'lint_warnings': lint_warnings,
    'translation_engines': ['Gemma 4 E2B / LiteRT-LM', 'LMT / GGUF profile 0', 'HY-MT2 / GGUF profile 1'],
    'native_libraries_present': required,
    'gguf_profiles_share_native_library': True,
    'weights_bundled': False,
    'phone_inference_verified': False,
    'broad_translation_quality_accepted': False,
    'apk_bytes': apk.stat().st_size,
    'pr_head_sha': os.environ.get('PR_HEAD_SHA', ''),
    'tested_merge_sha': os.environ.get('GITHUB_SHA', ''),
    'workflow_run_id': os.environ.get('GITHUB_RUN_ID', '')
}
Path('dist/validation.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
