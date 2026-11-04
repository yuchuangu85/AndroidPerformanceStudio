#!/usr/bin/env python3
"""Exercise argv exported by the Kotlin backend, not a parallel sandbox implementation."""
import json
import os
from pathlib import Path
import platform
import selectors
import subprocess
import sys
import tempfile
import time


def main():
    if platform.system() != "Linux":
        raise SystemExit("Linux host required")
    tools = json.loads(Path(sys.argv[1]).read_text())
    if tools["namespaceSmokeExit"] != 0:
        raise SystemExit("Namespace startup not qualified")
    templates = json.loads(Path(sys.argv[2]).read_text())
    root = Path(tempfile.mkdtemp(prefix="aps-linux-qualification-")).resolve()
    root.chmod(0o700)
    print("RAW_PROBE_ROOT=" + str(root), flush=True)
    script = root / "hostile.py"
    script.write_bytes(Path(__file__).with_name("linux-hostile.py").read_bytes())
    script.chmod(0o600)
    node_script = root / "hostile.cjs"
    node_script.write_bytes(Path(__file__).with_name("node-hostile.cjs").read_bytes())
    node_script.chmod(0o600)
    sentinel = root / "synthetic-host-private"
    sentinel.write_text("synthetic APS sentinel\n")
    sentinel.chmod(0o600)
    records = []

    def descendants(pid):
        found = set()
        pending = [pid]
        while pending:
            parent = pending.pop()
            try:
                children = (Path("/proc") / str(parent) / "task" / str(parent) / "children").read_text()
            except FileNotFoundError:
                continue
            for child in map(int, children.split()):
                if child not in found:
                    found.add(child)
                    pending.append(child)
        return found

    def alive(pid):
        try:
            status = (Path("/proc") / str(pid) / "stat").read_text()
            return status.rsplit(")", 1)[1].split()[0] not in ("Z", "X")
        except FileNotFoundError:
            return False

    def run(case, runtime="python"):
        substitutions = {"__APS_PRLIMIT__": "/usr/bin/prlimit", "__APS_BWRAP__": tools["bwrap"],
                         "__APS_HELPER__": tools["helper"],
                         "/__APS_SCRIPT__": str(script if runtime == "python" else node_script)}
        command = [substitutions.get(value, value) for value in templates[runtime]]
        process = subprocess.Popen(command, cwd=root, env={}, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        process.stdin.write(json.dumps({"case": case, "sentinel": str(sentinel)}).encode())
        process.stdin.close()
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        streams = {"stdout": bytearray(), "stderr": bytearray()}
        observed = set()
        started = time.monotonic()
        reason = "exit"
        try:
            while selector.get_map() or process.poll() is None:
                observed.update(descendants(process.pid))
                elapsed = time.monotonic() - started
                if case == "cancel" and elapsed > .25:
                    reason = "cancelled"
                    process.kill()
                elif elapsed > 5:
                    reason = "timeout"
                    process.kill()
                for key, _ in selector.select(.01):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    streams[key.data].extend(chunk)
                    if sum(map(len, streams.values())) > 65536:
                        reason = "output-limit"
                        process.kill()
                if elapsed > 7:
                    raise AssertionError("native process/pipe termination did not complete")
            code = process.wait(timeout=1)
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=2)
            selector.close()
            process.stdout.close()
            process.stderr.close()
        for _ in range(100):
            if not any(alive(pid) for pid in observed):
                break
            time.sleep(.01)
        remaining = sorted(pid for pid in observed if alive(pid))
        for name, content in streams.items():
            (root / (runtime + "-" + case + "." + name)).write_bytes(content[:65536])
        record = {"case": case, "runtime": runtime, "argv": command, "exit": code, "reason": reason,
                  "elapsedSeconds": round(time.monotonic() - started, 3),
                  "stdoutBytes": len(streams["stdout"]), "stderrBytes": len(streams["stderr"]),
                  "observedDescendants": sorted(observed), "remainingLiveDescendants": remaining}
        records.append(record)
        (root / "results.json").write_text(json.dumps(records, indent=2) + "\n")
        print(json.dumps(record), flush=True)
        assert not remaining, "sandbox descendants survived"
        assert sentinel.read_text() == "synthetic APS sentinel\n"
        if case == "cpu":
            assert code != 0 and reason == "exit" and time.monotonic() - started < 4
        elif case == "cancel":
            assert reason == "cancelled" and code != 0 and streams["stdout"] == b"APS_READY\n"
        elif case == "output":
            assert reason == "output-limit"
        elif case == "alternate-abi":
            assert code != 0 and reason == "exit"
        elif case == "memory" and runtime == "node":
            assert streams["stdout"].startswith(b"APS_MEMORY_READY\n")
            assert reason == "exit"
            assert (code == 0 and streams["stdout"].endswith(b"APS_PASS memory\n")) or (
                code != 0 and b"Allocation failed" in streams["stderr"])
        else:
            assert code == 0 and streams["stdout"].decode().strip() == "APS_PASS " + case

    for case in ("boundary", "network", "fork-descendants", "ipc-keys", "memory", "scratch", "file-size", "cpu", "output", "cancel"):
        run(case)
    if platform.machine() == "x86_64":
        run("alternate-abi")
    print("PYTHON_NATIVE_CASES_PASSED=" + str(len(records)))
    for case in ("boundary", "network", "fork-descendants", "memory", "scratch", "file-size", "cpu", "output", "cancel"):
        run(case, "node")
    print("NODE_NATIVE_CASES_PASSED=9")


if __name__ == "__main__":
    main()
