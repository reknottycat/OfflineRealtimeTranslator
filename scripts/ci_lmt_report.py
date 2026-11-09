#!/usr/bin/env python3
import json
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
if totals['failures'] or totals['errors']:
    raise SystemExit(f'Unit test failure: {totals}')
apk = Path('dist/OfflineRealtimeTranslator-Gemma4-LMT-HYMT2-debug.apk')
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    required = ['lib/arm64-v8a/liblmt_translator.so', 'lib/arm64-v8a/liblitertlm_jni.so']
    for library in required:
        if library not in names:
            raise SystemExit(f'Missing backend: {library}')
    if any(name.endswith(('.gguf', '.litertlm')) for name in names):
        raise SystemExit('Model weights must not be embedded in the APK')
result = {'unit_tests': totals, 'native_backends_present': required, 'weights_bundled': False,
          'engines': ['gemma4', 'lmt', 'hymt2'], 'phone_inference_verified': False, 'apk_bytes': apk.stat().st_size}
Path('dist/validation.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
