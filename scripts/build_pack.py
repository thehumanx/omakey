#!/usr/bin/env python3
"""Assembles a downloadable language pack: `build/packs/<id>-<packVersion>.zip` (AGENTS.md §66 Phase 7).

A pack is data only — manifest, layouts, profile, language model. Its hand-written parts live in
`languages/<id>/`; the model is built by `build_lang_lm.py` (or reused with --reuse-model).

The zip is deterministic (sorted entries, fixed timestamps, stored uncompressed) so rebuilding
unchanged inputs gives a byte-identical file and the same SHA-256. Stored rather than deflated also
means the installer can extract `lm.bin` without inflating 5 MB of data on a phone, and the model
barely compresses anyway.

Usage:
    scripts/build_pack.py es_ES [--reuse-model] [--min-app VERSION_CODE]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FIXED_TIME = (2020, 1, 1, 0, 0, 0)


def model_metadata(model: Path) -> dict:
    """The metadata section of a format-2 model (see build_lm.write_binary)."""
    data = model.read_bytes()
    magic, version = struct.unpack_from("<4sI", data, 0)
    if magic != b"OMLM" or version != 2:
        raise SystemExit(f"{model} is not a format-2 omakey model")
    alphabet_size, metadata_bytes = struct.unpack_from("<II", data, 32)
    offset = 64 + alphabet_size * 2 + (-(alphabet_size * 2) % 4)
    return json.loads(data[offset:offset + metadata_bytes].decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lang")
    parser.add_argument("--reuse-model", action="store_true", help="use build/packs/<id>/lm.bin as is")
    parser.add_argument("--min-app", type=int, help="minAppVersionCode to write into the manifest")
    args = parser.parse_args()

    source = REPO / "languages" / args.lang
    if not (source / "manifest.json").is_file():
        raise SystemExit(f"no pack sources at {source}")
    work = REPO / "build/packs" / args.lang
    model = work / "lm.bin"
    if not (args.reuse_model and model.is_file()):
        builder = REPO / "scripts/build_lang_lm.py"
        result = subprocess.run([sys.executable, str(builder), args.lang, "--out", str(model)])
        if result.returncode != 0:
            return result.returncode

    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    if args.min_app is not None:
        manifest["minAppVersionCode"] = args.min_app
    # Sources come from the model itself, so the manifest can't disagree with what was built.
    manifest["sources"] = model_metadata(model).get("sources", [])

    files: dict[str, bytes] = {"manifest.json": json.dumps(manifest, ensure_ascii=False, indent=1).encode("utf-8")}
    for path in [manifest["profile"], *manifest["layouts"]]:
        files[path] = (source / path).read_bytes()
    files[manifest["model"]] = model.read_bytes()

    out = REPO / "build/packs" / f"{manifest['id']}-{manifest['packVersion']}.zip"
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, date_time=FIXED_TIME)
            info.external_attr = 0o644 << 16
            archive.writestr(info, files[name])
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"{out.relative_to(REPO)}  {out.stat().st_size:,} bytes  sha256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
