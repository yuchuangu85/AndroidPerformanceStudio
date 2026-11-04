#!/usr/bin/env python3
"""Disposable native Linux qualification. No host mounts, credentials, or daemon startup."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import uuid


def main():
    # Explicit existing daemon endpoint; an empty client config prevents credential-helper discovery.
    endpoint, jvm_root = sys.argv[1:3]
    prepare_only = sys.argv[3:] == ["--prepare-only"]
    if sys.argv[3:] and not prepare_only:
        raise SystemExit("Only --prepare-only is accepted")
    if not endpoint.startswith("unix://") or not Path(endpoint[7:]).is_socket():
        raise SystemExit("An already running local Docker daemon is required")
    jvm_root = Path(jvm_root).resolve()
    repo = Path(__file__).resolve().parents[2]
    root = Path(tempfile.mkdtemp(prefix="aps-native-docker-")).resolve()
    root.chmod(0o700)
    config = root / "empty-client-config"
    config.mkdir(mode=0o700)
    docker = ["/usr/local/bin/docker", "--config", str(config), "--host", endpoint]
    print("RAW_PROBE_ROOT=" + str(root), flush=True)
    records = []

    def run(name, argv, data=None, check=True, timeout=300):
        result = subprocess.run(docker + argv, input=data, capture_output=True, timeout=timeout,
                                env={"PATH": "/usr/local/bin:/usr/bin:/bin"})
        (root / (name + ".stdout")).write_bytes(result.stdout)
        (root / (name + ".stderr")).write_bytes(result.stderr)
        records.append({"name": name, "argv": argv, "exit": result.returncode})
        (root / "operations.json").write_text(json.dumps(records, indent=2) + "\n")
        print(json.dumps(records[-1]), flush=True)
        if check and result.returncode:
            print(result.stderr.decode(errors="replace"), flush=True)
            raise RuntimeError(name + " failed")
        return result

    name = "aps-native-qualification-" + uuid.uuid4().hex[:12]
    image = "public.ecr.aws/ubuntu/ubuntu:24.04"
    cached_image = "aps-native-sandbox-tools:ubuntu24.04-arm64-v1"
    cache_label = "com.androidperformancestudio.sandbox-tools"
    created = False
    try:
        cached = run("cached-tools", ["image", "inspect", "--format",
                                      '{{index .Config.Labels "' + cache_label + '"}} {{.Architecture}}',
                                      cached_image], check=False)
        has_tools = cached.returncode == 0 and cached.stdout.decode().strip() == "tools-v1 arm64"
        if not has_tools:
            local = run("cached-base", ["image", "inspect", "--format", "{{.Architecture}}", image], check=False)
            if local.returncode or local.stdout.decode().strip() != "arm64":
                raise RuntimeError("Cached arm64 base image required; no automatic pull")
        container = run("create", ["create", "--name", name, "--platform", "linux/arm64",
                                   "--network", "none" if has_tools else "bridge",
                                   "--security-opt", "seccomp=unconfined", "--security-opt", "apparmor=unconfined",
                                   "--memory", "768m", "--pids-limit", "128", "--cpus", "2",
                                   cached_image if has_tools else image, "/bin/sleep", "1800"])
        name = container.stdout.decode().strip()
        if len(name) != 64 or any(char not in "0123456789abcdef" for char in name):
            raise RuntimeError("Invalid container identity")
        created = True
        run("start", ["start", name])
        if not has_tools:
            run("apt-update", ["exec", name, "/usr/bin/env", "-i", "PATH=/usr/bin:/bin",
                               "DEBIAN_FRONTEND=noninteractive", "/usr/bin/apt-get", "update"])
            run("apt-tools", ["exec", name, "/usr/bin/env", "-i", "PATH=/usr/bin:/bin",
                              "DEBIAN_FRONTEND=noninteractive", "/usr/bin/apt-get", "install", "-y", "--no-install-recommends",
                              "bubblewrap", "gcc", "libc6-dev", "python3", "nodejs", "openjdk-21-jre-headless"])
            run("cache-tools", ["commit", "--change", "LABEL " + cache_label + "=tools-v1", name, cached_image])
            run("disconnect-network", ["network", "disconnect", "bridge", name])
        run("container-boundary", ["inspect", "--format",
                                   "{{json .Mounts}} {{json .NetworkSettings.Networks}} {{.Image}}", name])
        inspected = run("node-runtime-files", ["exec", "-i", name, "/usr/bin/env", "-i", "PATH=/usr/bin:/bin",
                                                "/usr/bin/python3", "-"],
                        (repo / "scripts/ai-sandbox/inspect-node-runtime.py").read_bytes())
        print(inspected.stdout.decode(), flush=True)
        if prepare_only:
            return
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w") as bundle:
            for relative in ("scripts/ai-sandbox", "desktop-viewer/ai-platform/ai-platform-script-runtime/src/main/native"):
                bundle.add(repo / relative, arcname="aps-sources/" + relative)
            bundle.add(jvm_root / "commands.json", arcname="aps-sources/commands.json")
            bundle.add(jvm_root / "classes", arcname="aps-sources/jvm/classes")
            compile_command = json.loads((jvm_root / "compile-command.json").read_text())
            dependencies = compile_command[compile_command.index("-classpath") + 1].split(os.pathsep)
            for dependency in map(Path, dependencies):
                if dependency.is_file():
                    bundle.add(dependency, arcname="aps-sources/jvm/lib/" + dependency.name)
                else:
                    key = "model" if "ai-platform-model" in str(dependency) else "registry"
                    bundle.add(dependency, arcname="aps-sources/jvm/" + key)
        run("copy-sources", ["cp", "-", name + ":/tmp"], archive.getvalue())
        prefix = ["exec", "--user", "65534:65534", name, "/usr/bin/env", "-i", "PATH=/usr/bin:/bin"]
        prepared = run("prepare", prefix + ["/usr/bin/python3", "/tmp/aps-sources/scripts/ai-sandbox/prepare-linux.py"])
        tool_root = next(line.removeprefix("RAW_PROBE_ROOT=") for line in prepared.stdout.decode().splitlines()
                         if line.startswith("RAW_PROBE_ROOT="))
        qualified = run("hostile", prefix + ["/usr/bin/python3", "/tmp/aps-sources/scripts/ai-sandbox/qualify-linux.py",
                                             tool_root + "/tools.json", "/tmp/aps-sources/commands.json"], check=False)
        print(qualified.stdout.decode(errors="replace"), flush=True)
        roots = [tool_root]
        roots += [line.removeprefix("RAW_PROBE_ROOT=") for line in qualified.stdout.decode().splitlines()
                  if line.startswith("RAW_PROBE_ROOT=")]
        jvm_work = run("jvm-work", prefix + ["/usr/bin/mktemp", "-d", "/tmp/aps-linux-jvm-XXXXXXXX"])
        work = jvm_work.stdout.decode().strip()
        roots.append(work)
        tested = run("jvm-tests", prefix + ["/usr/bin/java", "-cp", "/tmp/aps-sources/jvm/classes:/tmp/aps-sources/jvm/model:"
                                           "/tmp/aps-sources/jvm/registry:/tmp/aps-sources/jvm/lib/*",
                                           "com.androidperformancestudio.aiplatform.script.NativeSandboxChecks", work,
                                           "/usr/bin/bwrap",
                                           tool_root + "/aps-script-sandbox"], check=False)
        print(tested.stdout.decode(errors="replace"), flush=True)
        for index, path in enumerate(roots):
            run("evidence-" + str(index), ["cp", name + ":" + path, str(root / ("linux-" + str(index)))])
        if qualified.returncode or tested.returncode:
            raise RuntimeError("Linux qualification failed; inspect raw logs")
    finally:
        if created:
            run("remove-container", ["rm", "-f", name], check=False)
    print("RAW_PROBE_ROOT=" + str(root), flush=True)


if __name__ == "__main__":
    main()
