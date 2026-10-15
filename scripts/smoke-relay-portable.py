#!/usr/bin/env python3
"""External synthetic CLI smoke test; never shipped as node telemetry."""
import argparse
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time


def run(binary, args, env, ok=True):
    result = subprocess.run([str(binary), *args], env=env, capture_output=True,
                            encoding="utf-8", timeout=10, check=False)
    assert (result.returncode == 0) == ok, (args, result.returncode, result.stderr)
    if not ok:
        assert "DO_NOT_PRINT" not in result.stdout + result.stderr
    return result


def call(sock, command, body=b""):
    sock.sendall(bytes([1, command]) + bytes(32) + struct.pack("!I", len(body)) + body)
    stream = sock.makefile("rb")
    try:
        header = stream.read(6)
        assert len(header) == 6 and header[0] == 1, header
        size = struct.unpack("!I", header[2:])[0]
        assert size <= 131145
        body = stream.read(size)
        assert len(body) == size
        return header[1], body
    finally:
        stream.close()


def live(binary, args, env, port):
    process = subprocess.Popen([str(binary), *args], env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 10
        while True:
            assert process.poll() is None, "node exited before connection"
            try:
                sock = socket.create_connection(("127.0.0.1", port), timeout=1)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise AssertionError("node did not start")
                time.sleep(.02)
        with sock:
            sock.settimeout(3)
            assert call(sock, 6) == (0, b"")
            assert call(sock, 3) == (2, b"\0")  # NO_QUEUE with index zero
            assert call(sock, 1, bytes(91)) == (7, b"")  # DENIED, never bypass signature
            assert call(sock, 6) == (0, b"")
    finally:
        process.terminate()
        try:
            stdout, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise AssertionError("node did not terminate")
        assert not stdout and not stderr, "running node emitted output"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    binary = parser.parse_args().binary.resolve(strict=True)
    env = {k: v for k, v in os.environ.items()
           if not k.startswith("SM_RELAY_") and k != "CREDENTIALS_DIRECTORY"}
    with tempfile.TemporaryDirectory(prefix="relay-portable-") as temp:
        # Spaces and Unicode paths must work on both native platforms.
        folder = Path(temp) / "local узел"
        folder.mkdir()
        config = folder / "relay.conf"
        run(binary, ["init", "--config", str(config)], env)
        original = config.read_bytes()
        run(binary, ["init", "--config", str(config)], env, ok=False)
        assert config.read_bytes() == original
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
            content = config.read_text(encoding="utf-8").replace("127.0.0.1:7400", f"127.0.0.1:{port}")
            config.write_text(content.replace("MODE=http", "MODE=tcp"), encoding="utf-8")
            # Explicit-file mode must ignore poisoned legacy environment and not bind a socket.
            poisoned = dict(env, SM_RELAY_LISTEN="0.0.0.0:1", SM_RELAY_MAX_CONNECTIONS="0",
                            SM_RELAY_GUEST_KEY_FILE="DO_NOT_PRINT", CREDENTIALS_DIRECTORY="DO_NOT_PRINT")
            check = run(binary, ["check", "--config", str(config)], poisoned)
            assert not check.stdout and not check.stderr
        live(binary, ["start", "--config", str(config)], poisoned, port)
        legacy = dict(env, SM_RELAY_LISTEN=f"127.0.0.1:{port}", SM_RELAY_MODE="tcp")
        live(binary, [], legacy, port)
        for extra in ["UNKNOWN=DO_NOT_PRINT\n", "SM_RELAY_MAX_QUEUES=0\n",
                      "SM_RELAY_ROOT_KEYS=DO_NOT_PRINT\n"]:
            config.write_text(content + extra, encoding="utf-8")
            run(binary, ["check", "--config", str(config)], env, ok=False)
            run(binary, ["start", "--config", str(config)], env, ok=False)
        assert "Loopback only" in run(binary, ["--help"], env).stdout
        assert run(binary, ["--version"], env).stdout.startswith("sm-relay ")
        for args in [["start"], ["check", "--config", str(config), "extra"], ["wat"]]:
            run(binary, args, env, ok=False)
    print("portable CLI smoke: OK (init/check/start, legacy env, PING, NO_QUEUE, DENIED, no traffic output)")


if __name__ == "__main__":
    main()
