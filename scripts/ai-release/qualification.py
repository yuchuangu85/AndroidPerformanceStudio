#!/usr/bin/env python3
"""Nonpublishing qualification gates. No credentials or provider output are logged."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SANDBOX_MODULE = "desktop-app"
PROVIDER_MODULE = "ai-platform-app"
SANDBOX_SUITE = "com.androidperformancestudio.desktop.AiReleaseQualificationTest"
# Filesystem timestamp granularity can exceed the gate's own runtime; a report written in the
# same tick as `started` must still count as fresh.
MTIME_TICK_TOLERANCE_SECONDS = 2.0
PROVIDER_SUITE = "com.androidperformancestudio.aiplatform.app.LiveProviderAcceptanceTest"


class GateError(Exception):
    pass


def require_environment(names, environment=None):
    environment = os.environ if environment is None else environment
    missing = [name for name in names if not environment.get(name, "").strip()]
    if missing:
        raise GateError("Missing environment references: " + ", ".join(missing))


def verify_junit(directory, suite_name, started, method=None):
    """Only fresh, nonempty, fully executed results of the exact suite count."""
    report = Path(directory) / ("TEST-" + suite_name + ".xml")
    # A report written within the same filesystem timestamp tick as `started` can carry an
    # mtime that rounds to just before `started`; tolerate that sub-tick window instead of
    # rejecting a report the gate itself just produced.
    if not report.is_file() or report.is_symlink() or report.stat().st_mtime < started - MTIME_TICK_TOLERANCE_SECONDS:
        raise GateError("Missing or stale qualification report")
    if report.stat().st_size > 4 * 1024 * 1024:
        raise GateError("Oversized qualification report")
    try:
        suite = ET.parse(report).getroot()
        cases = suite.findall("testcase")
        counts = {key: int(suite.get(key, "-1")) for key in ("tests", "failures", "errors", "skipped")}
    except (ET.ParseError, ValueError):
        raise GateError("Invalid qualification report") from None
    if (suite.tag != "testsuite" or suite.get("name") != suite_name or not cases
            or counts != {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
            or any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped"))):
        raise GateError("Qualification requires nonzero tests with zero failures, errors and skips")
    if method and (len(cases) != 1 or cases[0].get("name", "").removesuffix("()") != method):
        raise GateError("Qualification did not execute the exact native/live acceptance method")


def qualify(kind, system=None, environment=None, run=subprocess.run):
    system = platform.system() if system is None else system
    environment = dict(os.environ if environment is None else environment)
    if kind == "real-provider":
        if environment.get("APS_RUN_LIVE_PROVIDER") != "true":
            raise GateError("Real-provider acceptance requires explicit opt-in")
        provider = environment.get("APS_LIVE_PROVIDER")
        if provider not in ("openai", "codex", "claude"):
            raise GateError("Explicitly select openai, codex (Responses API) or claude")
        credential = "ANTHROPIC_API_KEY" if provider == "claude" else "OPENAI_API_KEY"
        require_environment(("APS_LIVE_MODEL", credential), environment)
        model = environment["APS_LIVE_MODEL"]
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", model):
            raise GateError("Explicit valid APS_LIVE_MODEL reference is required")
        if provider == "codex" and not re.fullmatch(r"(?:[A-Za-z0-9][A-Za-z0-9._:-]*-)?codex(?:[-.:][A-Za-z0-9._:-]+)?", model):
            raise GateError("Codex Responses API acceptance requires an explicit Codex-named model")
        # Never inherit JVM flags which might dump credentials/headers, or the other provider's key.
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS",
                     "OPENAI_API_KEY" if provider == "claude" else "ANTHROPIC_API_KEY"):
            environment.pop(name, None)
        module, suite = PROVIDER_MODULE, PROVIDER_SUITE
        method = "liveProviderAcceptance"
    else:
        if system not in ("Linux", "Darwin", "Windows"):
            raise GateError("Unsupported native platform")
        if system != "Linux" and environment.get("APS_RUN_NATIVE_SANDBOX_ACCEPTANCE") != "true":
            raise GateError("macOS/Windows native sandbox qualification requires explicit opt-in and a real backend")
        module, suite = SANDBOX_MODULE, SANDBOX_SUITE
        method = "nativeSandboxQualification"
        environment["APS_REQUIRE_NATIVE_SANDBOX"] = "true"
        environment["APS_REQUIRE_LINUX_SANDBOX"] = "true" if system == "Linux" else "false"
        environment["APS_NATIVE_FIXTURE_SECRET"] = "host-only-sentinel"
    base = ROOT / "desktop-viewer" if module == SANDBOX_MODULE else ROOT / "desktop-viewer/ai-platform"
    source = base / module / "src/test/kotlin" / Path(*suite.split(".")).with_suffix(".kt")
    if not source.is_file():
        raise GateError("Required native acceptance suite is not implemented: " + suite)
    viewer = ROOT / "desktop-viewer"
    wrapper = viewer / ("gradlew.bat" if system == "Windows" else "gradlew")
    task = ":" + module + ":test" if module == SANDBOX_MODULE else ":ai-platform:" + module + ":test"
    args = [str(wrapper), "-p", str(viewer), task, "--tests", suite + "." + method,
            "--rerun-tasks", "--no-build-cache", "--no-configuration-cache", "--no-daemon", "--no-scan",
            "--max-workers=1", "--console=plain"]
    started = time.time()
    # Gradle/tests may include response bodies in failures. Never forward their output or archive raw XML.
    with tempfile.TemporaryDirectory(prefix="aps-release-acceptance-") as temporary:
        receipt = Path(temporary).resolve() / "receipt.json"
        if kind == "real-provider":
            environment["APS_LIVE_RECEIPT"] = str(receipt)
        with open(os.devnull, "wb") as sink:
            result = run(args, cwd=ROOT, env=environment, stdout=sink, stderr=sink, timeout=1800, check=False)
        if result.returncode:
            raise GateError("Native acceptance execution failed; raw provider/test output withheld")
        verify_junit(base / module / "build/test-results/test", suite, started, method)
        if kind == "real-provider":
            verify_live_receipt(receipt, environment["APS_LIVE_PROVIDER"], environment["APS_LIVE_MODEL"])


def verify_live_receipt(path, provider, model):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 8192:
        raise GateError("Missing or invalid live acceptance receipt")
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        raise GateError("Invalid live acceptance receipt; sensitive details withheld") from None
    keys = {"provider", "runtimeVersion", "model", "discoveryStatus", "invocationStatus", "resultStatus",
            "brokerInvocationCount", "startedEventCount", "structuredResultCount", "transportAttemptCount"}
    if (not isinstance(data, dict) or data.keys() != keys or data["provider"] != provider or data["model"] != model
            or data["discoveryStatus"] != "AVAILABLE" or data["invocationStatus"] != "COMPLETED" or data["resultStatus"] != "PASSED"
            or any(type(data[key]) is not int or data[key] != 1 for key in ("brokerInvocationCount", "startedEventCount", "structuredResultCount"))
            or (provider == "claude" and data["transportAttemptCount"] is not None)
            or (provider != "claude" and (type(data["transportAttemptCount"]) is not int or data["transportAttemptCount"] != 1))
            or not isinstance(data["runtimeVersion"], str)
            or (provider != "claude" and data["runtimeVersion"] != provider + "-responses-v1")
            or (provider == "claude" and not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+ \(Claude Code\)", data["runtimeVersion"]))):
        raise GateError("Live acceptance receipt does not prove one successful selected-provider call")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gate", choices=("native-sandbox", "real-provider"))
    args = parser.parse_args()
    try:
        qualify(args.gate)
    except (GateError, OSError, subprocess.SubprocessError):
        # GateError text is host-authored and includes reference names, never values or subprocess errors.
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, GateError) else "Qualification command failed; details withheld", file=sys.stderr)
        return 1
    print("Qualification passed: " + args.gate + " on " + platform.system())
    return 0


if __name__ == "__main__":
    sys.exit(main())
