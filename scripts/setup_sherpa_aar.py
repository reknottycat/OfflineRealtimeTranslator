#!/usr/bin/env python3
"""Obtain official, pinned sherpa-onnx Android AAR for the GigaAM v2 ASR engine.

The binary is intentionally not committed to the public source repository.
Run before Gradle builds, or let the GitHub Actions job prepare it.
"""
import hashlib
import os
from pathlib import Path
import time
from urllib.request import Request, urlopen

URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
EXPECTED = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
TARGET = Path(__file__).resolve().parent.parent / "app" / "libs" / "sherpa-onnx-2.0.1.aar"
MAX_BYTES = 100 * 1024 * 1024

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def main():
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    if TARGET.is_file() and sha256(TARGET) == EXPECTED:
        print(f"Verified local sherpa AAR: {TARGET}")
        return
    TARGET.unlink(missing_ok=True)
    error = None
    for attempt in range(3):
        temporary = TARGET.with_suffix(".download")
        temporary.unlink(missing_ok=True)
        try:
            request = Request(URL, headers={"User-Agent": "OfflineRealtimeTranslator-GigaAM/4.0"})
            count = 0
            with urlopen(request, timeout=120) as src, temporary.open("wb") as dst:
                while True:
                    block = src.read(1024 * 1024)
                    if not block:
                        break
                    count += len(block)
                    if count > MAX_BYTES:
                        raise RuntimeError("Unexpected oversized AAR response")
                    dst.write(block)
                dst.flush()
                os.fsync(dst.fileno())
            digest = sha256(temporary)
            if digest != EXPECTED:
                raise RuntimeError(f"Sherpa AAR SHA-256 mismatch: {digest}")
            temporary.replace(TARGET)
            print(f"Verified official Sherpa AAR {count} bytes: {TARGET}")
            return
        except Exception as exc:
            error = exc
            temporary.unlink(missing_ok=True)
            print(f"Sherpa AAR download attempt {attempt + 1} failed: {exc}", flush=True)
            time.sleep(attempt + 1)
    raise SystemExit(f"Cannot verify sherpa-onnx 2.0.1 AAR: {error}")

if __name__ == "__main__":
    main()
