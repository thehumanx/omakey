#!/usr/bin/env python3
"""Writes and signs `build/packs/index.json` for the pack zips in build/packs (AGENTS.md §66 Phase 7).

Signs with keystore/pack-signing-key.pem (ECDSA P-256, gitignored — back it up with the release
keystore; losing it means shipping a new public key in an app update before any pack can change).

It does not upload anything. Publishing is a separate, deliberate step:

    gh release create packs --title "Language packs" --notes "..." --latest=false   # once
    gh release upload packs build/packs/index.json build/packs/index.json.sig build/packs/<id>-<version>.zip --clobber

`--latest=false` matters: the app's update checker reads /releases/latest, and a "packs" release
marked latest would feed it a tag that is not an app version.

Usage:
    scripts/publish_index.py [--key PATH]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PACKS = REPO / "build/packs"


def version_key(version: str) -> tuple:
    return tuple(int(p) if p.isdigit() else 0 for p in version.split("."))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", type=Path, default=REPO / "keystore/pack-signing-key.pem")
    args = parser.parse_args()

    newest: dict[str, tuple[tuple, dict]] = {}
    for path in sorted(PACKS.glob("*.zip")):
        with zipfile.ZipFile(path) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        if not re.fullmatch(r"[A-Za-z0-9_.-]+\.zip", path.name):
            raise SystemExit(f"unpublishable file name {path.name}")
        entry = {
            "id": manifest["id"],
            "displayName": manifest["displayName"],
            "nativeName": manifest["nativeName"],
            "packVersion": manifest["packVersion"],
            "packFormat": manifest["packFormat"],
            "minAppVersionCode": manifest.get("minAppVersionCode", 0),
            "sizeBytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "file": path.name,
            "license": manifest.get("license", ""),
        }
        key = version_key(manifest["packVersion"])
        if manifest["id"] not in newest or key > newest[manifest["id"]][0]:
            newest[manifest["id"]] = (key, entry)

    index = {"formatVersion": 1, "generated": date.today().isoformat(),
             "packs": [entry for _, entry in sorted(newest.values(), key=lambda kv: kv[1]["id"])]}
    index_path = PACKS / "index.json"
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.key), "-out", str(PACKS / "index.json.sig"),
                    str(index_path)], check=True)
    # Verify with the public half before anything can be uploaded.
    public = subprocess.run(["openssl", "ec", "-in", str(args.key), "-pubout"], capture_output=True, check=True).stdout
    pub_path = PACKS / ".index-verify.pub"
    pub_path.write_bytes(public)
    subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(pub_path), "-signature", str(PACKS / "index.json.sig"),
                    str(index_path)], check=True, capture_output=True)
    pub_path.unlink()
    for entry in index["packs"]:
        print(f"{entry['id']} {entry['packVersion']}  {entry['file']}  {entry['sizeBytes']:,} bytes")
    print(f"wrote and verified {index_path.relative_to(REPO)} + .sig")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
