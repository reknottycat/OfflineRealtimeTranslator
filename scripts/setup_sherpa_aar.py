#!/usr/bin/env python3
"""Fetch pinned native Android dependencies without committing their binaries.

The default uses upstream Sherpa for CPU. The optional QNN preview uses the
XDcobra Sherpa and ONNX Runtime build pair, Qualcomm's Maven QNN Runtimes,
and Google's LiteRT Qualcomm dispatch library for the Qwen ASR model.
The Qualcomm runtime is separately licensed:
https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf
"""

import argparse
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import tempfile
import time
from urllib.request import Request, urlopen


LIBS = Path(__file__).resolve().parent.parent / "app" / "libs"
MAX_BYTES = 100 * 1024 * 1024


@dataclass(frozen=True)
class Archive:
    name: str
    url: str
    sha256: str


CPU = Archive(
    "sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/"
    "sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
    "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575",
)
QNN = (
    Archive(
        "sherpa-onnx-2.0.1-1-qnn.aar",
        "https://github.com/XDcobra/react-native-sherpa-onnx/releases/download/"
        "sherpa-onnx-android-v2.0.1-1/sherpa-onnx-2.0.1-1.aar",
        "7f505d7984d3cce311c740ff72225b1fbdb8988dc5033bad352a95fac6c1ac01",
    ),
    Archive(
        "onnxruntime-1.28.1-xdcobra.aar",
        "https://xdcobra.github.io/maven/com/xdcobra/sherpa/onnxruntime/"
        "1.28.1-qnn2.43.1.260218-1/onnxruntime-1.28.1-qnn2.43.1.260218-1.aar",
        "67a2a6661a005bb1943100f8b28ca9864de225d956dc1b105b8047f3230c3649",
    ),
    Archive(
        "qnn-runtime-2.40.0.aar",
        "https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/"
        "2.40.0/qnn-runtime-2.40.0.aar",
        "a39572b77013f2c58657d05f08cad36ab256cc44402df746de5850b3b6d94397",
    ),
    Archive(
        "qnn-runtime-2.50.0.aar",
        "https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/"
        "2.50.0/qnn-runtime-2.50.0.aar",
        "b507656e4031d8aa6a7ab0fecf725b941096737ad7440ad7ea288f7c9cc9d743",
    ),
    Archive(
        "litert-npu-runtime-qualcomm-2.3.0.aar",
        "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/"
        "litert-npu-runtime-qualcomm/2.3.0/litert-npu-runtime-qualcomm-2.3.0.aar",
        "55aa22e89b7decb9e2f56548188b9e8c42cc8f7a828e2ec4a17715079b313679",
    ),
)


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download(archive):
    target = LIBS / archive.name
    if target.is_file() and sha256(target) == archive.sha256:
        print(f"Verified local dependency: {target.name}")
        return

    for attempt in range(3):
        temporary = None
        try:
            request = Request(archive.url, headers={"User-Agent": "OfflineRealtimeTranslator/4.3"})
            count = 0
            with tempfile.NamedTemporaryFile(dir=LIBS, suffix=".download", delete=False) as dest:
                temporary = Path(dest.name)
                with urlopen(request, timeout=120) as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        count += len(block)
                        if count > MAX_BYTES:
                            raise RuntimeError("Unexpected oversized AAR response")
                        dest.write(block)
                dest.flush()
                os.fsync(dest.fileno())
            digest = sha256(temporary)
            if digest != archive.sha256:
                raise RuntimeError(f"SHA-256 mismatch: {digest}")
            temporary.replace(target)
            print(f"Verified {target.name} ({count} bytes)")
            return
        except (OSError, RuntimeError) as exc:
            if attempt == 2:
                raise SystemExit(f"Cannot verify {archive.name}: {exc}") from exc
            print(f"{archive.name} attempt {attempt + 1} failed: {exc}", flush=True)
            time.sleep(attempt + 1)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qnn", action="store_true", default=os.environ.get("QNN_ENABLED") == "1")
    args = parser.parse_args()
    LIBS.mkdir(parents=True, exist_ok=True)
    for archive in QNN if args.qnn else (CPU,):
        download(archive)


if __name__ == "__main__":
    main()
