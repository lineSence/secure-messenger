#!/usr/bin/env python3
"""Reviewed native PNG goldens as a versioned hash manifest; verify never overwrites it.
PNG artifacts from each CI run retain the images for visual review. No HTML substitutes.
"""
import hashlib
import json
import pathlib
import struct
import sys

HERE = pathlib.Path(__file__).resolve().parent
BASELINE = HERE / "goldens.json"


def snapshot():
    rows = {}
    for path in sorted((HERE / "out").rglob("*.png")):
        data = path.read_bytes()
        if data[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"Not PNG: {path}")
        width, height = struct.unpack(">II", data[16:24])
        rows[str(path.relative_to(HERE / "out"))] = {
            "size": [width, height], "sha256": hashlib.sha256(data).hexdigest()
        }
    if not rows:
        raise ValueError("No native screenshots")
    return rows


def verify(expected, actual):
    different = sorted(key for key in expected.keys() | actual.keys() if expected.get(key) != actual.get(key))
    if different:
        print("UI regression (changed/missing/unexpected):\n" + "\n".join(different), file=sys.stderr)
        return False
    print(f"Native UI goldens match: {len(actual)} images")
    return True


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) == 2 else "verify"
    actual = snapshot()
    if mode == "record":
        BASELINE.write_text(json.dumps(actual, indent=2) + "\n")
        print(f"Recorded {len(actual)} candidate goldens: visually review before commit")
    elif mode == "verify":
        sys.exit(0 if verify(json.loads(BASELINE.read_text()), actual) else 1)
    else:
        sys.exit("Usage: verify.py [record|verify]")
