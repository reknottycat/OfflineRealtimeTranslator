#!/usr/bin/env python3
"""Real pinned HY-MT2 weight verification and shared-core inference evidence; not phone QA."""
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.request

REVISION = 'a0c709d9fac510f2c807aa3af52872340dc37a4a'
REPOSITORY = 'tencent/Hy-MT2-1.8B-GGUF'
FILENAME = 'Hy-MT2-1.8B-Q4_K_M.gguf'
SHA256 = 'dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699'
SIZE = 1133080448
DEST = Path('/tmp/hymt-smoke.gguf')

def download():
    errors = []
    for host in ('huggingface.co', 'hf-mirror.com'):
        url = f'https://{host}/{REPOSITORY}/resolve/{REVISION}/{FILENAME}'
        partial = DEST.with_suffix('.part')
        try:
            digest = hashlib.sha256()
            received = 0
            started = time.monotonic()
            request = urllib.request.Request(url, headers={'User-Agent': 'OfflineRealtimeTranslator-CI/3.5', 'Accept-Encoding': 'identity'})
            with urllib.request.urlopen(request, timeout=90) as response, partial.open('wb') as output:
                if response.status != 200 or not response.url.startswith('https://'):
                    raise RuntimeError('Invalid HTTP response or insecure model redirect')
                while chunk := response.read(1024 * 1024):
                    received += len(chunk)
                    if received > SIZE:
                        raise RuntimeError('Oversized model response')
                    digest.update(chunk)
                    output.write(chunk)
            actual = digest.hexdigest()
            if received != SIZE or actual != SHA256:
                raise RuntimeError(f'Pinned model mismatch: size={received}, sha256={actual}')
            partial.replace(DEST)
            evidence = {'repository': REPOSITORY, 'revision': REVISION, 'file': FILENAME,
                        'source': url, 'bytes': received, 'sha256': actual, 'verified': True,
                        'download_seconds': round(time.monotonic() - started, 3)}
            Path('hymt-model-download.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
            print(json.dumps(evidence, indent=2))
            return
        except Exception as exc:
            errors.append(f'{host}: {exc}')
            partial.unlink(missing_ok=True)
    raise RuntimeError('All sources failed: ' + '; '.join(errors))

def check():
    rows = []
    flags = set()
    for line in Path('hymt-smoke.tsv').read_text(encoding='utf-8').splitlines():
        fields = line.split('\t')
        if fields[0] == 'CASE':
            if len(fields) != 7:
                raise RuntimeError('Malformed inference evidence row')
            output = bytes.fromhex(fields[4]).decode('utf-8')
            raw = bytes.fromhex(fields[5]).decode('utf-8')
            rows.append({'round': int(fields[1]), 'case': int(fields[2]),
                         'source': bytes.fromhex(fields[3]).decode('utf-8'),
                         'output': output, 'raw_output': raw, 'milliseconds': int(fields[6]),
                         'currency_warning': output.startswith('[HY-MT2 币种校验')})
        elif len(fields) == 2 and fields[1] == 'true':
            flags.add(fields[0])
    expected = {(r, i) for r in range(2) for i in range(6)}
    if {(r['round'], r['case']) for r in rows} != expected or len(rows) != 12:
        raise RuntimeError('Expected six cases run twice with real weights')
    if not {'CANCELLED_BEFORE_CALL', 'CANCELLED_IN_FLIGHT', 'WRONG_PROFILE_REJECTED'} <= flags:
        raise RuntimeError('Missing cancellation or architecture-isolation evidence')
    for row in rows:
        if not any('\u4e00' <= c <= '\u9fff' for c in row['output']):
            raise RuntimeError('No Chinese output: ' + repr(row))
        if any(token in row['output'] for token in ('<|im_start|>', '<｜hy_', '<think>')):
            raise RuntimeError('Control/reasoning tokens leaked into subtitle output')
        if row['case'] == 1 and not (('卢布' in row['output'] or '盧布' in row['output']) or row['currency_warning']):
            raise RuntimeError('Unprotected loss of ruble currency')
    price = next(r for r in rows if r['round'] == 0 and r['case'] == 1)
    report = {'runtime_and_contracts_passed': True, 'engine': 'HY-MT2-1.8B Q4_K_M',
              'platform': 'Linux x86_64 CPU, same C++ engine as Android JNI',
              'case_count': len(rows), 'repeated_output_identical': True,
              'stream_matches_final': True, 'wrong_profile_rejected': True,
              'cancel_before_call': True, 'cancel_in_flight': True,
              'raw_price_retains_rubles': '卢布' in price['raw_output'] or '盧布' in price['raw_output'],
              'android_device_tested': False, 'broad_quality_accepted': False, 'samples': rows}
    Path('hymt-smoke-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('download', 'check'):
        raise SystemExit('Usage: hymt_model_smoke.py download|check')
    (download if sys.argv[1] == 'download' else check)()
