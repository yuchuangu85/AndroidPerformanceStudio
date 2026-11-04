"""Bounded hostile fixtures. Run ONLY through the exported native sandbox command."""
import ctypes
import errno
import json
import mmap
import os
from pathlib import Path
import platform
import socket
import subprocess
import sys
import threading
import time


config = json.load(sys.stdin)
case = config["case"]


def denied(action, codes=None):
    try:
        action()
    except OSError as failure:
        if codes is not None:
            assert failure.errno in codes, failure.errno
        return
    raise AssertionError("forbidden operation succeeded")


if case == "boundary":
    sentinel = Path(config["sentinel"])
    denied(lambda: sentinel.read_bytes())
    denied(lambda: sentinel.open("wb"))
    denied(lambda: Path("/input/skill").open("wb"))
    assert Path("/input/skill").read_bytes()
    for directory in ("/work", "/tmp"):
        output = Path(directory) / "output"
        output.write_bytes(b"positive-control")
        assert output.read_bytes() == b"positive-control"
        output.unlink()
        link = Path(directory) / "host-link"
        link.symlink_to(sentinel)
        denied(lambda: link.read_bytes())
        denied(lambda: link.open("wb"))
    assert not Path("/Users").exists()
    assert not Path("/usr/local").exists()
    assert Path("/dev/shm").is_dir()
    denied(lambda: Path("/dev/shm/forbidden").open("wb"), (errno.EROFS, errno.EACCES))
    denied(lambda: Path("/dev/forbidden").open("wb"), (errno.EROFS, errno.EACCES))
elif case == "network":
    for family in (socket.AF_INET, socket.AF_INET6, socket.AF_UNIX):
        denied(lambda: socket.socket(family, socket.SOCK_STREAM), (errno.EPERM, errno.EACCES))
    denied(socket.socketpair, (errno.EPERM, errno.EACCES))
elif case == "fork-descendants":
    try:
        child = os.fork()
    except OSError as failure:
        assert failure.errno == errno.EPERM
    else:
        if child == 0:
            os._exit(0)
        os.waitpid(child, 0)
        raise AssertionError("fork allowed")
    denied(lambda: subprocess.run([sys.executable, "-I", "-B", "-c", "pass"], check=True), (errno.EPERM,))
    # Interpreter threads share one address space and one process CPU budget.
    values = []
    thread = threading.Thread(target=lambda: values.append("thread-ok"))
    thread.start()
    thread.join()
    assert values == ["thread-ok"]
elif case == "memory":
    try:
        value = bytearray(320 * 1024 * 1024)
    except MemoryError:
        pass
    else:
        raise AssertionError("address-space budget exceeded")
    denied(lambda: mmap.mmap(-1, 320 * 1024 * 1024), (errno.ENOMEM,))
elif case == "ipc-keys":
    libc = ctypes.CDLL(None, use_errno=True)
    segment = libc.shmget(0, 4096, 0o600)
    if segment >= 0:
        libc.shmctl(segment, 0, None)
        raise AssertionError("SysV shared memory allowed")
    assert ctypes.get_errno() == errno.EPERM
    keyctl_number = {"x86_64": 250, "aarch64": 219}[platform.machine()]
    assert libc.syscall(keyctl_number, 0, 0, 0, 0, 0) == -1
    assert ctypes.get_errno() == errno.EPERM
elif case == "cpu":
    while True:
        pass
elif case == "scratch":
    for directory in ("/work", "/tmp"):
        for index in range(128):
            try:
                (Path(directory) / str(index)).write_bytes(b"x" * 4096)
            except OSError as failure:
                assert failure.errno == errno.ENOSPC
                break
        else:
            raise AssertionError("tmpfs quota exceeded")
elif case == "file-size":
    denied(lambda: Path("/work/large").write_bytes(b"x" * 131072), (errno.EFBIG, errno.ENOSPC))
elif case == "output":
    os.write(1, b"o" * 40000)
    os.write(2, b"e" * 40000)
elif case == "cancel":
    print("APS_READY", flush=True)
    time.sleep(60)
elif case == "alternate-abi":
    # x86_64: seccomp must kill x32 syscalls before a fork-number interpretation.
    ctypes.CDLL(None).syscall(0x40000000 | 57)
    raise AssertionError("alternate syscall ABI survived")
else:
    raise AssertionError("unknown fixture")

if case != "output":
    print("APS_PASS " + case)
