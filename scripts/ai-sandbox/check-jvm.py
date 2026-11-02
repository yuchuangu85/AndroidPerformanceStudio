#!/usr/bin/env python3
"""Compile/test only the owned module using already cached jars; never invokes Gradle."""
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    repo = Path(__file__).resolve().parents[2]
    module = repo / "desktop-viewer/ai-platform/ai-platform-script-runtime"
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    root = Path(tempfile.mkdtemp(prefix="aps-sandbox-jvm-")).resolve()
    print("RAW_PROBE_ROOT=" + str(root), flush=True)

    def jar(group, artifact, version=None):
        base = cache / group / artifact
        if version:
            base /= version
        matches = sorted(p for p in base.glob("**/*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
        if not matches:
            raise SystemExit("Missing cached jar: " + group + ":" + artifact)
        return str(matches[-1])

    version = "2.4.20"
    compiler = [jar("org.jetbrains.kotlin", name, version) for name in
                ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
    compiler += [jar("org.jetbrains.kotlin", "kotlin-reflect"),
                 jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"),
                 jar("org.jetbrains", "annotations")]
    dependencies = [jar("org.jetbrains.kotlin", name, version) for name in
                    ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit5")]
    dependencies += [jar("org.jetbrains.kotlinx", name, "1.11.0") for name in
                     ("kotlinx-coroutines-core-jvm", "kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm")]
    dependencies += [jar("org.junit.jupiter", "junit-jupiter-api"),
                     jar("org.junit.platform", "junit-platform-commons"),
                     jar("org.opentest4j", "opentest4j"), jar("org.apiguardian", "apiguardian-api"),
                     jar("org.jetbrains", "annotations")]
    for name in ("model", "registry"):
        dependency = repo / ("desktop-viewer/ai-platform/ai-platform-" + name + "/build/classes/kotlin/main")
        if not dependency.is_dir():
            raise SystemExit("Missing existing upstream classes: " + str(dependency))
        dependencies.append(str(dependency))
    sources = sorted(str(path) for tree in ("main", "test") for path in (module / "src" / tree / "kotlin").rglob("*.kt"))
    plugin = jar("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", version)
    command = ["/usr/bin/java", "-cp", ":".join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-Werror", "-jvm-target", "17", "-Xplugin=" + plugin,
               "-classpath", ":".join(dependencies), "-d", str(root / "classes")] + sources
    (root / "compile-command.json").write_text(json.dumps(command, indent=2) + "\n")
    compiled = subprocess.run(command, cwd=repo, capture_output=True, timeout=120)
    (root / "compile.stdout").write_bytes(compiled.stdout)
    (root / "compile.stderr").write_bytes(compiled.stderr)
    if compiled.returncode:
        print(compiled.stderr.decode(errors="replace"))
        raise SystemExit(compiled.returncode)
    run = subprocess.run(["/usr/bin/java", "-cp", ":".join([str(root / "classes")] + dependencies),
                          "com.androidperformancestudio.aiplatform.script.NativeSandboxChecks", str(root)],
                         cwd=repo, capture_output=True, timeout=60)
    (root / "tests.stdout").write_bytes(run.stdout)
    (root / "tests.stderr").write_bytes(run.stderr)
    print(run.stdout.decode(errors="replace"))
    if run.returncode:
        print(run.stderr.decode(errors="replace"))
    raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
