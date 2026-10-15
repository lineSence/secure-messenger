#!/usr/bin/env python3
"""Package already-tested native relay executables; no signing or network publication."""
import argparse
import hashlib
import re
from pathlib import Path
import tarfile
import tempfile
import zipfile

TARGETS = {"x86_64-unknown-linux-gnu": "sm-relay", "x86_64-pc-windows-msvc": "sm-relay.exe"}
README = """Uncensored relay — EXPERIMENTAL LOCAL-ONLY TEST BUILD

Not a signed/public release. No CGNAT tunnel, TLS endpoint, HA or OS RAM-only proof.
Do not expose the loopback protocol on the Internet. No compiler is needed to run it.
Linux GNU x64: built on Ubuntu 22.04; not a static/musl or all-distro guarantee.
Windows MSVC x64: static CRT requested; tested on native CI, not all home PCs.

Linux commands (PowerShell: replace ./sm-relay with ./sm-relay.exe):
  ./sm-relay init --config relay.conf
  ./sm-relay check --config relay.conf
  ./sm-relay start --config relay.conf

init never overwrites. It generates PUBLIC settings using the existing project root.
Replace SM_RELAY_ROOT_KEYS with your trusted owner's PUBLIC 33-byte HEX key.
Never copy the private owner root key onto the node. No new owner is created by init.
The same existing quotas/timeouts apply; default config RAM admission is 384 MiB.
check succeeds silently, performs no network probes, and does not bind a port.
--config mode ignores SM_RELAY_* environment variables and CREDENTIALS_DIRECTORY.
Optional guest-key file paths are relative to relay.conf, not the current directory.
Config syntax: UTF-8 KEY=VALUE, # line comments, no expansion/quotes/includes.
Unknown/duplicate keys fail. No arguments retains legacy environment/systemd mode.

Only configuration/authorized service keys may persist. Messages/queues are RAM-only.
Verify swap/pagefile, hibernation, dumps and OS memory limits before privacy deployment.
This build does NOT prove these OS requirements or change system settings for you.
Stopping/restarting the node loses its queues. No request logs/telemetry are enabled.

VERSION.txt identifies the exact source revision. SHA256SUMS checks integrity only;
it is NOT a digital signature or proof of publisher identity. Review/release signing
is a later gate. Full design and current status:
https://github.com/lineSence/secure-messenger/blob/main/wiki/architecture/relay-home.md
"""


def package(binary, target, sha, output):
    if target not in TARGETS or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("unsupported target or invalid source SHA")
    binary = Path(binary)
    if binary.is_symlink() or not binary.is_file() or binary.name != TARGETS[target]:
        raise ValueError("expected a regular native executable")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    name = f"sm-relay-test-{target}-{sha[:12]}"
    suffix = ".zip" if target.endswith("msvc") else ".tar.gz"
    dest = output / (name + suffix)
    if dest.exists():
        raise FileExistsError(dest)
    with tempfile.TemporaryDirectory(prefix="relay-package-") as temp:
        root = Path(temp) / name
        root.mkdir()
        executable = root / TARGETS[target]
        executable.write_bytes(binary.read_bytes())
        executable.chmod(0o755)
        (root / "README.txt").write_text(README, encoding="utf-8", newline="\n")
        (root / "VERSION.txt").write_text(f"source_sha={sha}\ntarget={target}\nchannel=experimental-local-only\n", encoding="ascii")
        files = sorted(root.iterdir())
        sums = "".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in files)
        (root / "SHA256SUMS").write_text(sums, encoding="ascii")
        files = sorted(root.iterdir())
        if suffix == ".zip":
            with zipfile.ZipFile(dest, "x", compression=zipfile.ZIP_DEFLATED) as archive:
                for path in files:
                    archive.write(path, arcname=f"{name}/{path.name}")
        else:
            with tarfile.open(dest, "x:gz") as archive:
                for path in files:
                    # Set Unix archive permissions explicitly: Windows chmod/stat
                    # cannot represent an executable 0755 mode for the Linux fixture.
                    info = archive.gettarinfo(str(path), arcname=f"{name}/{path.name}")
                    info.mode = 0o755 if path.name == TARGETS[target] else 0o644
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    with path.open("rb") as source:
                        archive.addfile(info, source)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    dest.with_name(dest.name + ".sha256").write_text(f"{digest}  {dest.name}\n", encoding="ascii")
    return dest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    print(package(args.binary, args.target, args.source_sha, args.out))


if __name__ == "__main__":
    main()
