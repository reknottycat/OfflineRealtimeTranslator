#!/usr/bin/env python3
"""Real-weight smoke test. No claims of phone/GPU performance are inferred from this CPU test."""
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import urllib.request

REVISION = "f03d51b43463d8a457eee1cd765220de69e9bac8"
SHA256 = "54c6244f100330264cef607188413ee82cb8a254f0cf950011aaa9cee74f7eb5"
URL = f"https://huggingface.co/mradermacher/LMT-60-1.7B-GGUF/resolve/{REVISION}/LMT-60-1.7B.Q4_K_M.gguf"

def download():
    path = Path('/tmp/lmt-smoke.gguf')
    last = None
    for attempt in range(3):
        try:
            digest = hashlib.sha256()
            size = 0
            with urllib.request.urlopen(URL, timeout=90) as source, path.open('wb') as target:
                while chunk := source.read(1024 * 1024):
                    size += len(chunk)
                    if size > 1_600_000_000:
                        raise ValueError('Oversized model response')
                    target.write(chunk)
                    digest.update(chunk)
            if digest.hexdigest() != SHA256:
                raise ValueError('LMT model digest mismatch')
            report = {'url': URL, 'revision': REVISION, 'sha256': SHA256, 'actual_bytes': size, 'verified': True}
            Path('lmt-model-download.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print(json.dumps(report))
            return
        except Exception as exc:
            last = exc
            path.unlink(missing_ok=True)
            if attempt < 2:
                time.sleep(3)
    raise RuntimeError('Could not download and verify the pinned model') from last

def check():
    lines = Path('lmt-smoke.tsv').read_text(encoding='utf-8').splitlines()
    if len(lines) != 2:
        raise AssertionError(f'Expected two actual translations, received {lines!r}')
    samples = []
    for line, number in zip(lines, ['300', '1000']):
        source, translated = line.split('\t', 1)
        if not re.search(r'[\u3400-\u9fff]', translated):
            raise AssertionError(f'No Chinese in output: {translated!r}')
        if number not in translated:
            raise AssertionError(f'Number {number} was not preserved: {translated!r}')
        if '<|im_' in translated or 'Translate the following' in translated:
            raise AssertionError('Template/prompt leaked into output')
        samples.append({'russian': source, 'chinese': translated, 'number_preserved': number})
    report = {'passed': True, 'platform': 'Linux x86_64 CPU', 'shared_core': 'app/src/main/cpp/lmt/lmt_engine.hpp',
              'sampling': 'greedy; not the paper beam-search benchmark', 'samples': samples,
              'cancellation_asserted_by_executable': True, 'android_device_tested': False}
    Path('lmt-smoke-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in {'download', 'check'}:
        raise SystemExit('Usage: lmt_model_smoke.py download|check')
    (download if sys.argv[1] == 'download' else check)()
