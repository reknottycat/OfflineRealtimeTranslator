#!/usr/bin/env python3
"""Bounded, read-only Range probes for the recorded frozen model artifacts.

No authentication, full-model download, payload persistence, or signed redirect
URL logging. Run --list first to inspect the exact public URLs to be requested.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import json
from pathlib import Path
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request


MIB = 1024 * 1024
MAGIC = {"hy": b"GGUF", "gemma": b"LITERTLM", "lmt": b"GGUF"}
SAFE_HEADERS = {
    "content-type",
    "content-length",
    "content-range",
    "content-encoding",
    "accept-ranges",
    "etag",
    "x-linked-etag",
    "x-linked-size",
}
CONTENT_RANGE = re.compile(r"bytes\s+(\d+)-(\d+)/(\d+)", re.IGNORECASE)


class HTTPSOnlyRedirect(urllib.request.HTTPRedirectHandler):
    """Preserve urllib's redirect limit while refusing a TLS downgrade."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme.lower() != "https":
            # Do not put the target URL (which may be signed) in the exception.
            raise ValueError("non_https_redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def selected_sources(manifest: dict, args: argparse.Namespace) -> list[dict]:
    sources = []
    for model in manifest["models"]:
        model_id = model["model_id"]
        if args.model and model_id not in args.model:
            continue
        candidates = list(model["sources"])
        if args.include_candidate_proxies:
            for proxy in manifest["user_requested_proxy_prefixes"]:
                host = urllib.parse.urlsplit(proxy["prefix"]).hostname
                candidates.append(
                    {
                        "name": f"{model_id}-proxy-{host}",
                        "url": proxy["prefix"] + model["hf_origin_url"],
                        "candidate_only": True,
                    }
                )
        for source in candidates:
            if args.source and source["name"] not in args.source:
                continue
            url = source["url"]
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme != "https" or parsed.username or parsed.password:
                raise ValueError("manifest_source_must_be_public_https")
            sources.append(
                {
                    "model_id": model_id,
                    "name": source["name"],
                    "url": url,
                    "expected_total": model["size_bytes"],
                    "expected_file_sha256": model["sha256"],
                    "candidate_only": source.get("candidate_only", False),
                }
            )
    return sources


def safe_headers(headers) -> dict[str, str]:
    return {k.lower(): v for k, v in (headers or {}).items() if k.lower() in SAFE_HEADERS}


def read_bounded(response, limit: int, deadline: float) -> bytes:
    """One bounded buffered read at a time; socket timeout still applies."""
    parts = []
    received = 0
    read = getattr(response, "read1", response.read)
    while received < limit:
        if time.monotonic() >= deadline:
            raise TimeoutError("sample_read_deadline")
        part = read(min(64 * 1024, limit - received))
        if not part:
            break
        parts.append(part)
        received += len(part)
    return b"".join(parts)


def probe(source: dict, *, chunk: bool, timeout: float) -> dict:
    start, end = (4 * MIB, 8 * MIB - 1) if chunk else (0, 63)
    expected_length = end - start + 1
    requested_range = f"bytes={start}-{end}"
    result = dict(source)
    result.update(requested_range=requested_range, range_validated=False)
    begin = time.monotonic()
    request = urllib.request.Request(
        source["url"],
        headers={"Range": requested_range, "Accept-Encoding": "identity"},
        method="GET",
    )
    # build_opener retains the process's normal ProxyHandler configuration.
    opener = urllib.request.build_opener(HTTPSOnlyRedirect())
    try:
        with opener.open(request, timeout=timeout) as response:
            result["status"] = response.status
            # Never persist response.url: it can contain temporary credentials.
            result["final_host"] = urllib.parse.urlsplit(response.geturl()).hostname
            headers = safe_headers(response.headers)
            result["headers"] = headers
            reasons = []
            if response.status != 206:
                reasons.append("expected_http_206")
            matched = CONTENT_RANGE.fullmatch(headers.get("content-range", "").strip())
            if not matched or tuple(map(int, matched.groups())) != (
                start,
                end,
                source["expected_total"],
            ):
                reasons.append("wrong_content_range_or_total")
            content_type = headers.get("content-type", "").split(";", 1)[0].strip().lower()
            if content_type.startswith("text/") or any(
                bad in content_type for bad in ("html", "json", "xml")
            ):
                reasons.append("non_binary_content_type")
            if headers.get("content-encoding", "identity").lower() != "identity":
                reasons.append("unexpected_content_encoding")
            length_header = headers.get("content-length")
            if length_header is not None:
                try:
                    if int(length_header) != expected_length:
                        reasons.append("wrong_content_length")
                except ValueError:
                    reasons.append("invalid_content_length")
            if reasons:
                # Do not consume a potentially full-model 200 response or an
                # HTML error body. Header failure is sufficient to reject it.
                result["errors"] = reasons
                result["received_bytes"] = 0
                return result
            data = read_bounded(response, expected_length + 1, begin + timeout)
            result["received_bytes"] = len(data)
            result["sample_sha256"] = hashlib.sha256(data).hexdigest()
            if len(data) != expected_length:
                reasons.append("wrong_received_length")
            if start == 0 and not data.startswith(MAGIC[source["model_id"]]):
                reasons.append("wrong_model_magic")
            if reasons:
                result["errors"] = reasons
            result["range_validated"] = not reasons
    except urllib.error.HTTPError as exc:
        result["status"] = exc.code
        result["headers"] = safe_headers(exc.headers)
        result["error_type"] = "http_error"
        # No remote body or exception string: either can include signed URLs.
        exc.close()
    except (TimeoutError, socket.timeout):
        result["error_type"] = "timeout"
    except urllib.error.URLError as exc:
        result["error_type"] = "url_error"
        result["cause_type"] = type(exc.reason).__name__
    except (ValueError, OSError, http.client.HTTPException) as exc:
        result["error_type"] = type(exc).__name__
    finally:
        result["seconds"] = round(time.monotonic() - begin, 3)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--quick", action="store_true", help="first 64 bytes (default)")
    modes.add_argument("--chunk", action="store_true", help="one nonzero 4 MiB sample")
    parser.add_argument("--model", action="append", choices=sorted(MAGIC))
    parser.add_argument("--source", action="append", help="exact source name from --list")
    parser.add_argument("--timeout", type=float, default=30.0, help="1–60 seconds; default 30")
    parser.add_argument("--include-candidate-proxies", action="store_true")
    parser.add_argument("--list", action="store_true", help="print sources without network calls")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 60:
        parser.error("--timeout must be between 1 and 60 seconds")
    manifest = json.loads(Path(__file__).with_name("sources.json").read_text(encoding="utf-8"))
    sources = selected_sources(manifest, args)
    if not sources:
        parser.error("no matching sources; use --list to inspect exact names")
    if args.list:
        print(json.dumps(sources, ensure_ascii=False, indent=2))
        return 0
    results = [probe(source, chunk=args.chunk, timeout=args.timeout) for source in sources]
    output = {
        "observed_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "probe_location": "execution environment; not established as a China mobile network",
        "china_device_tested": False,
        "full_model_download_performed": False,
        "full_file_sha256_verified": False,
        "mode": "nonzero_4_mib" if args.chunk else "first_64_bytes",
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if all(item["range_validated"] for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
