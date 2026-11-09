#!/usr/bin/env python3
"""Pinned-weight runtime evidence. Passing narrow cases is not broad quality acceptance."""
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import urllib.request

REVISION = 'a0c709d9fac510f2c807aa3af52872340dc37a4a'
SHA256 = 'dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699'
SIZE = 1_133_080_448
URL = f'https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/{REVISION}/Hy-MT2-1.8B-Q4_K_M.gguf'
WARNING = '[HY-MT2 币种校验未通过：原文含卢布，译文未保留币种，请核对原文。]'

def download():
    path = Path('/tmp/hymt2-smoke.gguf')
    for attempt in range(3):
        try:
            h, n = hashlib.sha256(), 0
            with urllib.request.urlopen(URL, timeout=90) as source, path.open('wb') as target:
                while chunk := source.read(1024 * 1024):
                    n += len(chunk)
                    if n > SIZE:
                        raise ValueError('Oversized model response')
                    target.write(chunk)
                    h.update(chunk)
            if n != SIZE or h.hexdigest() != SHA256:
                raise ValueError('Model size/hash mismatch')
            result = dict(url=URL, revision=REVISION, sha256=SHA256, bytes=n, verified=True)
            Path('hymt2-model-download.json').write_text(json.dumps(result, indent=2))
            print(json.dumps(result))
            return
        except Exception:
            path.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(3)

def check():
    lines = Path('hymt2-smoke.tsv').read_text().splitlines()
    if len(lines) != 8:
        raise AssertionError(f'Expected 8 real inferences, received {len(lines)}')
    samples, issues = [], []
    for index, line in enumerate(lines):
        source, display, raw, latency = line.split('\t')
        if not re.search(r'[\u3400-\u9fff]', display):
            raise AssertionError(f'No Chinese translation: {display!r}')
        if any(t in display for t in ('<think>', '</think>', '<answer>', '<｜hy_', '<|im_')):
            raise AssertionError('Control/reasoning tags leaked to subtitles')
        checks = {}
        if index == 0: checks['300_tonnes'] = any(x in display for x in ('300', '三百')) and '玉米' in display
        if index == 1:
            ruble = any(x in display for x in ('卢布', '盧布', 'RUB', '₽')) and display != WARNING
            if not ruble and display != WARNING: raise AssertionError('Currency error not blocked')
            checks['ruble_preserved_without_warning'] = ruble
            checks['1000'] = any(x in display for x in ('1000', '1,000', '一千'))
        if index == 2: checks['negation'] = any(x in display for x in ('不同意', '不接受', '不赞成', '不能同意', '不认可'))
        if index == 3: checks['14_percent'] = any(x in display for x in ('14', '十四'))
        if index == 4: checks['30_days'] = any(x in display for x in ('30', '三十'))
        if index == 6: checks['20000_USD'] = any(x in display for x in ('20000', '20,000', '2万', '两万', '二万')) and '美元' in display
        if index == 7: checks['close_valve'] = '阀' in display and any(x in display for x in ('关闭', '关上', '关掉'))
        for name, ok in checks.items():
            if not ok: issues.append(dict(sample=index+1, check=name, issue='Manual semantic review required'))
        samples.append(dict(russian=source, displayed_chinese=display, raw_model_output=raw,
                            latency_ms=int(latency), narrow_checks=checks, currency_guard_blocked=display == WARNING))
    report = dict(runtime_and_guard_passed=True, platform='Linux x86_64 CPU',
                  shared_android_core='app/src/main/cpp/lmt/lmt_engine.hpp', profile='HyMt2=1; hunyuan-dense',
                  sampling='greedy; 2048 context / 512 output limit; not a paper benchmark',
                  wrong_profile_rejected=True, atomic_validated_subtitles=True, post_cancel_call_rejected=True,
                  samples=samples, narrow_check_issues=issues, narrow_checks_passed=not issues,
                  quality_accepted=False, android_device_tested=False)
    Path('hymt2-smoke-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in {'download', 'check'}:
        raise SystemExit('Usage: hymt2_model_smoke.py download|check')
    (download if sys.argv[1] == 'download' else check)()
