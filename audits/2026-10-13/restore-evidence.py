#!/usr/bin/env python3
"""Restore the exact evidence ZIP; run from any directory."""
from pathlib import Path
import base64, hashlib
root = Path(__file__).resolve().parent
parts = sorted((root / "evidence").glob("archive.b64.part*"))
assert len(parts) == 11, "Missing archive parts"
data = base64.b64decode("".join(p.read_text().strip() for p in parts), validate=True)
assert hashlib.sha256(data).hexdigest() == "554c96e053125aef9b769ca6a26b43ccc16622d71bf0cdc0a237165edee29962", "Checksum mismatch"
out = root / "Uncensored-audit-evidence-2026-10-13.zip"
out.write_bytes(data)
print(out)
