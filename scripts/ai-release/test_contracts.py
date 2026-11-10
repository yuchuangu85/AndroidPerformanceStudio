#!/usr/bin/env python3
"""Deterministic local fixtures: no Gradle, signing tools, secrets, provider calls or publication."""

import base64
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

import qualification as gate
import sign

ROOT = Path(__file__).resolve().parents[2]


def job(workflow, name):
    return re.split(r"\n  (?=\S)", workflow.split("\n  " + name + ":\n", 1)[1], maxsplit=1)[0]


def mac_environment():
    return {
        "APS_ENABLE_RELEASE_SIGNING": "true",
        "APS_MACOS_CERTIFICATE_BASE64": base64.b64encode(b"fixture-not-a-certificate").decode(),
        "APS_MACOS_CERTIFICATE_PASSWORD": "fixture-sensitive-password",
        "APS_MACOS_SIGNING_IDENTITY": "Developer ID Application: Fixture (ABC1234567)",
        "APS_MACOS_INSTALLER_IDENTITY": "Developer ID Installer: Fixture (ABC1234567)",
        "APPLE_ID": "fixture-sensitive-account@example.invalid",
        "APPLE_TEAM_ID": "ABC1234567",
        "APPLE_APP_SPECIFIC_PASSWORD": "fixture-sensitive-notary-password",
    }


def live_receipt(provider, model):
    return {"provider": provider, "model": model, "runtimeVersion": "2.1.308 (Claude Code)" if provider == "claude" else provider + "-responses-v1",
            "discoveryStatus": "AVAILABLE", "invocationStatus": "COMPLETED", "resultStatus": "PASSED",
            "brokerInvocationCount": 1, "startedEventCount": 1, "structuredResultCount": 1,
            "transportAttemptCount": None if provider == "claude" else 1}


class WorkflowContracts(unittest.TestCase):
    def setUp(self):
        self.release = (ROOT / ".github/workflows/release.yml").read_text()

    def test_publication_requires_current_source_and_retains_packaging_gates(self):
        resolve = job(self.release, "resolve")
        self.assertIn('"$tag_sha" != "$GITHUB_SHA"', resolve)
        tests = job(self.release, "package-windows")
        self.assertIn("needs: [resolve, prepare-release, build-compose-agent]", tests)
        self.assertNotIn("ai-qualification", self.release)
        self.assertNotIn("\n  test:", self.release)

    def test_secrets_only_use_environment_references_never_inline_run_expressions(self):
        workflow = self.release
        in_run = False
        for line in workflow.splitlines():
            stripped = line.lstrip()
            if stripped.startswith("run:"):
                in_run = True
            elif stripped and len(line) - len(stripped) <= 8:
                in_run = False
            if in_run:
                self.assertNotIn("${{ secrets.", line)
        entitlements = plistlib.loads((ROOT / "scripts/ai-release/macos-entitlements.plist").read_bytes())
        self.assertNotIn("com.apple.security.get-task-allow", entitlements)
        self.assertNotIn("com.apple.security.cs.disable-library-validation", entitlements)

    def test_windows_native_signing_contract_and_parse_when_powershell_is_native(self):
        script = ROOT / "scripts/ai-release/sign-windows.ps1"
        source = script.read_text()
        for token in ("Get-PfxData", "Import-PfxCertificate", "Remove-Item -LiteralPath $certificatePath -DeleteKey",
                      "finally", "/fd SHA256", "/td SHA256", "/tr $env:APS_WINDOWS_TIMESTAMP_URL",
                      "verify /pa /all /tw", "Get-AuthenticodeSignature", "TimeStamperCertificate",
                      "Refusing to replace an existing signing identity"):
            self.assertIn(token, source)
        self.assertNotIn("-Exportable", source)
        if sys.platform == "win32":
            parse = "$tokens=$null; $errors=$null; [void][System.Management.Automation.Language.Parser]::ParseFile($env:APS_PS_AST_SCRIPT,[ref]$tokens,[ref]$errors); if($errors.Count){exit 1}"
            result = subprocess.run(["pwsh", "-NoProfile", "-NonInteractive", "-Command", parse],
                                    env={**os.environ, "APS_PS_AST_SCRIPT": str(script)}, capture_output=True, timeout=20, check=False)
            self.assertEqual(0, result.returncode, "PowerShell AST validation failed")


class QualificationFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="aps-qualification-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def report(self, contents):
        report = self.root / ("TEST-" + gate.SANDBOX_SUITE + ".xml")
        report.write_text(contents)
        return report

    def xml(self, tests="1", failures="0", errors="0", skipped="0", case="<testcase name='actual native execution'/>"):
        return (f"<testsuite name='{gate.SANDBOX_SUITE}' tests='{tests}' failures='{failures}' errors='{errors}' skipped='{skipped}'>"
                + case + "</testsuite>")

    def test_fresh_nonempty_native_report_only(self):
        self.report(self.xml())
        gate.verify_junit(self.root, gate.SANDBOX_SUITE, time.time() - 2)

    def test_offline_fixture_method_cannot_qualify_native_or_live_acceptance(self):
        self.report(self.xml(case="<testcase name='offline fixture'/>"))
        with self.assertRaisesRegex(gate.GateError, "exact native/live"):
            gate.verify_junit(self.root, gate.SANDBOX_SUITE, 0, "nativeSandboxQualification")

    def test_missing_empty_stale_malformed_and_every_skip_failure_are_rejected(self):
        with self.assertRaises(gate.GateError):
            gate.verify_junit(self.root, gate.SANDBOX_SUITE, 0)
        for contents in ("", "not xml", self.xml(tests="0", case=""), self.xml(skipped="1"),
                         self.xml(failures="1"), self.xml(errors="1"), self.xml(case="<testcase><skipped/></testcase>"),
                         self.xml(case="<testcase><failure/></testcase>"), self.xml(tests="9"), self.xml(tests="NaN")):
            with self.subTest(contents=contents):
                self.report(contents)
                with self.assertRaises(gate.GateError):
                    gate.verify_junit(self.root, gate.SANDBOX_SUITE, 0)
        report = self.report(self.xml())
        os.utime(report, (1, 1))
        with self.assertRaises(gate.GateError):
            gate.verify_junit(self.root, gate.SANDBOX_SUITE, time.time())

    def test_real_provider_opt_in_and_missing_credentials_fail_without_execution(self):
        for environment in ({}, {"APS_RUN_LIVE_PROVIDER": "true"}):
            with patch.object(gate.subprocess, "run") as runner:
                with self.assertRaises(gate.GateError):
                    gate.qualify("real-provider", environment=environment, run=runner)
                runner.assert_not_called()

    def test_unavailable_credentials_unknown_provider_and_implicit_codex_model_fail_closed(self):
        environment = {"APS_RUN_LIVE_PROVIDER": "true", "APS_LIVE_MODEL": "fixture-model",
                       "OPENAI_API_KEY": "fixture-sensitive-key"}
        for provider in ("claude", "codex", "fixture", "", "fixture-sensitive"):
            with self.subTest(provider=provider):
                with self.assertRaises(gate.GateError) as failure:
                    gate.qualify("real-provider", environment={**environment, "APS_LIVE_PROVIDER": provider})
                self.assertNotIn("fixture-sensitive", str(failure.exception))

    def test_selected_provider_credential_names_are_required_without_cli_login_fallback(self):
        for provider in ("openai", "codex", "claude"):
            key = "ANTHROPIC_API_KEY" if provider == "claude" else "OPENAI_API_KEY"
            wrong_key = "OPENAI_API_KEY" if provider == "claude" else "ANTHROPIC_API_KEY"
            environment = {"APS_RUN_LIVE_PROVIDER": "true", "APS_LIVE_PROVIDER": provider,
                           "APS_LIVE_MODEL": "fixture-codex-model" if provider == "codex" else "fixture-model",
                           wrong_key: "fixture-sensitive-wrong-provider-key"}
            with self.assertRaises(gate.GateError) as failure:
                gate.qualify("real-provider", environment=environment)
            self.assertIn(key, str(failure.exception))
            self.assertNotIn("fixture-sensitive", str(failure.exception))

    def test_one_filtered_live_invocation_routes_only_selected_key_and_deletes_metadata_receipt(self):
        source = self.root / "desktop-viewer/ai-platform" / gate.PROVIDER_MODULE / "src/test/kotlin"
        source = source / Path(*gate.PROVIDER_SUITE.split(".")).with_suffix(".kt")
        source.parent.mkdir(parents=True)
        source.write_text("fixture source only; never compiled")
        reports = self.root / "desktop-viewer/ai-platform" / gate.PROVIDER_MODULE / "build/test-results/test"
        reports.mkdir(parents=True)
        for provider in ("openai", "codex", "claude"):
            calls = []
            model = "fixture-codex-model" if provider == "codex" else "fixture-model"
            environment = {"APS_RUN_LIVE_PROVIDER": "true", "APS_LIVE_PROVIDER": provider, "APS_LIVE_MODEL": model,
                           "OPENAI_API_KEY": "fixture-sensitive-openai-key", "ANTHROPIC_API_KEY": "fixture-sensitive-anthropic-key",
                           "JAVA_TOOL_OPTIONS": "fixture-sensitive-http-debug", "GRADLE_OPTS": "fixture-debug"}

            def runner(args, **options):
                calls.append(options["env"])
                selected = "ANTHROPIC_API_KEY" if provider == "claude" else "OPENAI_API_KEY"
                other = "OPENAI_API_KEY" if provider == "claude" else "ANTHROPIC_API_KEY"
                self.assertIn(selected, options["env"])
                for forbidden in (other, "JAVA_TOOL_OPTIONS", "GRADLE_OPTS"):
                    self.assertNotIn(forbidden, options["env"])
                self.assertIn("--max-workers=1", args)
                self.assertIn("--no-scan", args)
                self.assertEqual(gate.PROVIDER_SUITE + ".liveProviderAcceptance", args[args.index("--tests") + 1])
                self.assertEqual(os.devnull, options["stdout"].name)
                Path(options["env"]["APS_LIVE_RECEIPT"]).write_text(json.dumps(live_receipt(provider, model)))
                (reports / ("TEST-" + gate.PROVIDER_SUITE + ".xml")).write_text(
                    f"<testsuite name='{gate.PROVIDER_SUITE}' tests='1' failures='0' errors='0' skipped='0'><testcase name='liveProviderAcceptance()'/></testsuite>")
                return SimpleNamespace(returncode=0)

            with patch.object(gate, "ROOT", self.root):
                gate.qualify("real-provider", system="Linux", environment=environment, run=runner)
            self.assertEqual(1, len(calls))
            self.assertFalse(Path(calls[0]["APS_LIVE_RECEIPT"]).exists())

    def test_live_receipt_rejects_wrong_api_adapter_missing_call_extra_data_and_fabricated_cli_transport(self):
        receipt = self.root / "live-receipt.json"
        base = live_receipt("codex", "fixture-codex-model")
        for key, wrong in (("runtimeVersion", "codex-cli 0.166.0"), ("resultStatus", "FAILED"),
                           ("brokerInvocationCount", 0), ("transportAttemptCount", 2), ("transportAttemptCount", True),
                           ("raw_response", "fixture-sensitive-provider-response")):
            receipt.write_text(json.dumps({**base, key: wrong}))
            with self.assertRaises(gate.GateError) as failure:
                gate.verify_live_receipt(receipt, "codex", "fixture-codex-model")
            self.assertNotIn("fixture-sensitive", str(failure.exception))
        receipt.write_text(json.dumps({**live_receipt("claude", "fixture-model"), "transportAttemptCount": 1}))
        with self.assertRaises(gate.GateError):
            gate.verify_live_receipt(receipt, "claude", "fixture-model")

    def test_absent_production_suite_is_failure_not_mock_acceptance(self):
        with patch.object(gate, "ROOT", self.root):
            with self.assertRaisesRegex(gate.GateError, "not implemented"):
                gate.qualify("native-sandbox", system="Linux", environment={})
            for system in ("Darwin", "Windows"):
                with self.assertRaisesRegex(gate.GateError, "opt-in"):
                    gate.qualify("native-sandbox", system=system, environment={})

    def test_native_invocation_forces_rerun_strict_mode_and_discards_raw_output(self):
        source = self.root / "desktop-viewer" / gate.SANDBOX_MODULE / "src/test/kotlin"
        source = source / Path(*gate.SANDBOX_SUITE.split(".")).with_suffix(".kt")
        source.parent.mkdir(parents=True)
        source.write_text("fixture source only; never compiled")
        reports = self.root / "desktop-viewer" / gate.SANDBOX_MODULE / "build/test-results/test"
        reports.mkdir(parents=True)

        def runner(args, **options):
            self.assertIn("--rerun-tasks", args)
            self.assertIn("--no-build-cache", args)
            self.assertIn("--no-configuration-cache", args)
            self.assertEqual(gate.SANDBOX_SUITE + ".nativeSandboxQualification", args[args.index("--tests") + 1])
            self.assertEqual("true", options["env"]["APS_REQUIRE_LINUX_SANDBOX"])
            self.assertEqual("true", options["env"]["APS_REQUIRE_NATIVE_SANDBOX"])
            self.assertEqual(os.devnull, options["stdout"].name)
            (reports / ("TEST-" + gate.SANDBOX_SUITE + ".xml")).write_text(self.xml(case="<testcase name='nativeSandboxQualification()'/>"))
            return SimpleNamespace(returncode=0)

        with patch.object(gate, "ROOT", self.root):
            gate.qualify("native-sandbox", system="Linux", environment={}, run=runner)
            with self.assertRaisesRegex(gate.GateError, "execution failed"):
                gate.qualify("native-sandbox", system="Linux", environment={}, run=lambda *a, **kw: SimpleNamespace(returncode=1))


class SigningFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="aps-signing-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_opt_in_and_every_missing_reference_fail_closed_without_values(self):
        with self.assertRaisesRegex(gate.GateError, "explicit|requires"):
            sign.preflight("macos", {})
        for name in sign.MAC_REFERENCES:
            environment = mac_environment()
            del environment[name]
            with self.assertRaises(gate.GateError) as failure:
                sign.preflight("macos", environment)
            self.assertIn(name, str(failure.exception))
            self.assertNotIn("fixture-sensitive", str(failure.exception))
        for identity in ("-", "Apple Development: Fixture (ABC1234567)", "Developer ID Application: Fixture (ZZZ1234567)"):
            with self.assertRaises(gate.GateError):
                sign.preflight("macos", {**mac_environment(), "APS_MACOS_SIGNING_IDENTITY": identity})
        with self.assertRaises(gate.GateError) as failure:
            sign.preflight("windows", {"APS_ENABLE_RELEASE_SIGNING": "true"})
        for name in sign.WINDOWS_REFERENCES:
            self.assertIn(name, str(failure.exception))

    def test_java_class_files_are_not_mistaken_for_universal_macho_payloads(self):
        path = self.directory / "fixture"
        for header, expected in ((b"\xca\xfe\xba\xbe\x00\x00\x00\x41", False),
                                 (b"\xca\xfe\xba\xbe\x00\x00\x00\x02", True),
                                 (b"\xcf\xfa\xed\xfe\x00\x00\x00\x00", True), (b"PK\x03\x04", False)):
            path.write_bytes(header)
            self.assertEqual(expected, sign.is_macho(path))

    def test_wrong_app_version_blocks_before_key_import_or_signing(self):
        app = self.directory / "Fixture.app"
        (app / "Contents").mkdir(parents=True)
        with (app / "Contents/Info.plist").open("wb") as stream:
            plistlib.dump({"CFBundleShortVersionString": "1.0.0", "CFBundleExecutable": "Fixture"}, stream)
        with patch.object(sign.platform, "system", return_value="Darwin"), patch.object(sign, "command") as runner:
            with self.assertRaisesRegex(gate.GateError, "bundle version"):
                sign.sign_macos(app, self.directory, "1.0.1", "arm64", self.directory)
            runner.assert_not_called()

    def test_complete_macos_pipeline_orders_nested_signing_notarization_assessment_and_cleanup(self):
        app = self.directory / "Fixture.app"
        (app / "Contents/MacOS").mkdir(parents=True)
        with (app / "Contents/Info.plist").open("wb") as stream:
            plistlib.dump({"CFBundleShortVersionString": "7.2", "CFBundleExecutable": "Fixture"}, stream)
        (app / "Contents/MacOS/Fixture").write_bytes(b"\xcf\xfa\xed\xfe\x00\x00\x00\x00")
        output = self.directory / "output"
        output.mkdir()
        calls = []

        def command(args):
            args = [str(arg) for arg in args]
            calls.append(args)
            if args[1:] in (["list-keychains", "-d", "user"], ["default-keychain", "-d", "user"]):
                return '"/fixture/login"'
            if "find-identity" in args:
                return '1) FIXTURE "' + mac_environment()["APS_MACOS_SIGNING_IDENTITY"] + '"'
            if args[0] == "/usr/bin/ditto" and len(args) == 3:
                shutil.copytree(args[1], args[2])
            if args[0] == "/usr/bin/hdiutil" and args[1] == "create" or args[0] == "/usr/bin/pkgbuild":
                Path(args[-1]).write_bytes(b"fixture artifact")
            if args[0] == "/usr/sbin/pkgutil":
                return mac_environment()["APS_MACOS_INSTALLER_IDENTITY"]
            if args[1:3] == ["notarytool", "submit"]:
                return json.dumps({"id": "00000000-0000-0000-0000-000000000001", "status": "Accepted"})
            return ""

        with patch.dict(os.environ, mac_environment(), clear=True), patch.object(sign.platform, "system", return_value="Darwin"), patch.object(sign, "command", side_effect=command):
            sign.sign_macos(app, output, "0.7.2", "arm64", self.directory)
        self.assertEqual(sign.names("macos", "0.7.2", "arm64"), set(sign.hashes(output, "macos", "0.7.2", "arm64")))
        submits = [index for index, args in enumerate(calls) if args[1:3] == ["notarytool", "submit"]]
        self.assertEqual(3, len(submits), "App, final DMG and final PKG must all be notarized")
        for index in submits:
            self.assertEqual(["stapler", "staple"], calls[index + 1][1:3])
            self.assertEqual(["stapler", "validate"], calls[index + 2][1:3])
            self.assertEqual("/usr/sbin/spctl", calls[index + 3][0])
        self.assertEqual("delete-keychain", calls[-1][1])
        signing = [args for args in calls if args[0] == "/usr/bin/codesign" and "--sign" in args]
        self.assertTrue(signing[0][-1].endswith("/Contents/MacOS/Fixture"))
        self.assertTrue(signing[1][-1].endswith("Fixture.app"))
        self.assertEqual(b"\xcf\xfa\xed\xfe\x00\x00\x00\x00", (app / "Contents/MacOS/Fixture").read_bytes())

    def test_subprocess_errors_never_expose_argv_or_output(self):
        secret = "fixture-sensitive-subprocess"
        for behavior in (subprocess.CalledProcessError(1, [secret], secret, secret),
                         subprocess.TimeoutExpired([secret], 1, secret, secret), OSError(secret)):
            with patch.object(sign.subprocess, "run", side_effect=behavior):
                with self.assertRaises(gate.GateError) as failure:
                    sign.command([secret])
                self.assertNotIn(secret, str(failure.exception))
        with patch.object(sign.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=secret, stderr=secret)):
            with self.assertRaises(gate.GateError) as failure:
                sign.command([secret])
            self.assertNotIn(secret, str(failure.exception))

    def test_temporary_keychain_restores_default_and_exact_search_list_after_import_failure(self):
        calls = []

        def command(args):
            calls.append([str(arg) for arg in args])
            if args[1:] == ["list-keychains", "-d", "user"]:
                return '"/Users/fixture/Library/Keychains/login.keychain-db"\n"/path with spaces/other.keychain-db"'
            if args[1:] == ["default-keychain", "-d", "user"]:
                return '"/Users/fixture/Library/Keychains/login.keychain-db"'
            if args[1] == "import":
                raise gate.GateError("fixture import failure")
            return ""

        with patch.dict(os.environ, mac_environment(), clear=True), patch.object(sign, "command", side_effect=command):
            with self.assertRaisesRegex(gate.GateError, "fixture import failure"):
                with sign.temporary_keychain(self.directory):
                    self.fail("Import failure must not qualify")
        self.assertEqual(["default-keychain", "-d", "user", "-s", "/Users/fixture/Library/Keychains/login.keychain-db"], calls[-3][1:])
        self.assertEqual(["list-keychains", "-d", "user", "-s", "/Users/fixture/Library/Keychains/login.keychain-db", "/path with spaces/other.keychain-db"], calls[-2][1:])
        self.assertEqual("delete-keychain", calls[-1][1])
        self.assertFalse((self.directory / "identity.p12").exists())

    def test_cleanup_failure_attempts_all_restoration_and_rejects_success(self):
        calls = []

        def command(args):
            calls.append(args)
            if args[1:] == ["list-keychains", "-d", "user"] or args[1:] == ["default-keychain", "-d", "user"]:
                return '"/fixture/login"'
            if args[1:5] == ["default-keychain", "-d", "user", "-s"] and args[-1] == "/fixture/login":
                raise gate.GateError("fixture cleanup failure")
            return ""

        with patch.dict(os.environ, mac_environment(), clear=True), patch.object(sign, "command", side_effect=command):
            with self.assertRaisesRegex(gate.GateError, "restoration/cleanup failed"):
                with sign.temporary_keychain(self.directory):
                    pass
        self.assertEqual("list-keychains", calls[-2][1])
        self.assertEqual("delete-keychain", calls[-1][1])

    def test_interrupt_inside_keychain_context_restores_state(self):
        calls = []

        def command(args):
            calls.append(args)
            if args[1:] in (["list-keychains", "-d", "user"], ["default-keychain", "-d", "user"]):
                return '"/fixture/login"'
            return ""

        with patch.dict(os.environ, mac_environment(), clear=True), patch.object(sign, "command", side_effect=command):
            with self.assertRaises(KeyboardInterrupt):
                with sign.temporary_keychain(self.directory):
                    raise KeyboardInterrupt()
        self.assertEqual("default-keychain", calls[-3][1])
        self.assertEqual("list-keychains", calls[-2][1])
        self.assertEqual("delete-keychain", calls[-1][1])

    def test_notary_requires_accepted_then_staple_and_validate(self):
        for status in ("Invalid", "In Progress", "Rejected", ""):
            with patch.object(sign, "command", return_value=json.dumps({"id": "00000000-0000-0000-0000-000000000001", "status": status})) as runner:
                with self.assertRaises(gate.GateError):
                    sign.notarize("fixture.zip", "fixture.app", "fixture.keychain")
                self.assertEqual(1, runner.call_count)
        calls = []

        def command(args):
            calls.append(args)
            return json.dumps({"id": "00000000-0000-0000-0000-000000000001", "status": "Accepted"})

        with patch.object(sign, "command", side_effect=command):
            sign.notarize("fixture.zip", "fixture.app", "fixture.keychain")
        self.assertIn("--wait", calls[0])
        self.assertIn("--keychain-profile", calls[0])
        self.assertEqual(["stapler", "staple", "fixture.app"], calls[1][1:])
        self.assertEqual(["stapler", "validate", "fixture.app"], calls[2][1:])

    def test_receipt_binds_exact_source_version_arch_and_bytes_not_unsigned_artifact(self):
        output = self.directory / "assets"
        output.mkdir()
        for name in sign.names("macos", "1.2.3", "arm64"):
            (output / name).write_bytes(b"fixture signed artifact, not native evidence")
        receipt = self.directory / "receipt.json"
        data = {"schema": 1, "status": "signed-notarized", "platform": "macos", "version": "1.2.3",
                "arch": "arm64", "source_sha": "a" * 40, "assets": sign.hashes(output, "macos", "1.2.3", "arm64")}
        receipt.write_text(json.dumps(data))
        sign.verify_receipt(receipt, output, "macos", "1.2.3", "arm64", "a" * 40)
        for key, wrong in (("status", "unsigned-development"), ("source_sha", "b" * 40), ("arch", "x64"), ("version", "1.2.4")):
            receipt.write_text(json.dumps({**data, key: wrong}))
            with self.assertRaises(gate.GateError):
                sign.verify_receipt(receipt, output, "macos", "1.2.3", "arm64", "a" * 40)
        receipt.write_text(json.dumps(data))
        next(iter(output.iterdir())).write_bytes(b"tampered")
        with self.assertRaises(gate.GateError):
            sign.verify_receipt(receipt, output, "macos", "1.2.3", "arm64", "a" * 40)
        receipt.unlink()
        with self.assertRaises(gate.GateError):
            sign.verify_receipt(receipt, output, "macos", "1.2.3", "arm64", "a" * 40)

    def test_default_cli_never_signs_and_redacts_missing_secret_values(self):
        environment = {**os.environ, "APS_ENABLE_RELEASE_SIGNING": "false", "APPLE_APP_SPECIFIC_PASSWORD": "fixture-sensitive-notary"}
        result = subprocess.run([sys.executable, "-B", str(sign.HERE / "sign.py"), "preflight", "macos"],
                                env=environment, capture_output=True, text=True, timeout=10, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("fixture-sensitive", result.stdout + result.stderr)
        self.assertNotIn("gate passed", result.stdout)


from test_signing_payload import PayloadFixtures
from test_publication import PublicationFixtures


if __name__ == "__main__":
    unittest.main(verbosity=2)
