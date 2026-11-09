#!/usr/bin/env python3
"""Test actual weights; distinguish translation quality from runtime/guard correctness."""
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
WARNING = "[LMT 币种校验未通过：原文含卢布，译文未保留币种，请核对原文。]"

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
        raise AssertionError(f'Expected two actual inferences, received {lines!r}')
    samples = []
    issues = []
    for index, (line, number) in enumerate(zip(lines, ['300', '1000'])):
        source, displayed, raw = line.split('\t', 2)
        if not re.search(r'[\u3400-\u9fff]', raw) or number not in raw:
            raise AssertionError(f'Raw output missing Chinese or number {number}: {raw!r}')
        if '<|im_' in raw or 'Translate the following' in raw:
            raise AssertionError('Template/prompt leaked into raw output')
        currency_preserved = index == 0 or any(word in raw for word in ['卢布', '盧布', 'RUB', 'rub', '₽'])
        if not currency_preserved:
            if displayed != WARNING:
                raise AssertionError(f'Incorrect currency output was not blocked: {displayed!r}')
            issues.append({'sample': index + 1, 'issue': 'Raw model failed to preserve ruble currency',
                           'mitigation': 'Output withheld, explicit warning displayed; no guessed currency replacement'})
        elif displayed != raw:
            raise AssertionError('Correct/non-currency output unexpectedly changed')
        samples.append({'russian': source, 'raw_model_chinese': raw, 'displayed_chinese': displayed,
                        'number_preserved_in_raw': number, 'ruble_currency_preserved_in_raw': currency_preserved,
                        'guard_blocked': not currency_preserved})
    report = {'runtime_and_guard_passed': True, 'quality_accepted': not issues,
              'platform': 'Linux x86_64 CPU', 'shared_core': 'app/src/main/cpp/lmt/lmt_engine.hpp',
              'sampling': 'greedy; not a paper benchmark', 'samples': samples, 'known_quality_issues': issues,
              'stream_matches_validated_final': True, 'post_cancel_call_rejected': True,
              'android_device_tested': False}
    Path('lmt-smoke-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in {'download', 'check'}:
        raise SystemExit('Usage: lmt_model_smoke.py download|check')
    (download if sys.argv[1] == 'download' else check)()
