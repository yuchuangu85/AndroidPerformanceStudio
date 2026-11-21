import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile


SPEC = importlib.util.spec_from_file_location("prepare_compose", Path(__file__).parents[1] / "prepare-compose-runtime.py")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class PrepareComposeRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="compose runtime with spaces ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.helper = self.root / "helper distribution"
        self.bundle = self.root / "agent bundle"
        self.java = self.root / "jdk bundle" / "Contents" / "Home"
        self.resources = self.root / "packaged resources"
        launcher = self.helper / "bin" / ("aps-compose-inspection.bat" if os.name == "nt" else "aps-compose-inspection")
        self.write(launcher, b"launcher")
        launcher.chmod(0o755)
        self.jar = self.helper / "lib" / "compose-inspection-host.jar"
        self.jar.parent.mkdir()
        self.write_jar(self.jar, PREPARE.MAIN_CLASS)
        self.write_jar(self.helper / "lib" / "dependency.jar", "Dependency.class")
        self.write(self.helper / "docs" / "NOTICE", b"entire distribution")
        manifest = ["# Built by :layout-inspector:compose-agent-build (composeAgentBundle)."]
        for key, relative in PREPARE.ARTIFACTS.items():
            content = relative.encode("ascii")
            self.write(self.bundle / relative, content)
            manifest.append(f"{key}={hashlib.sha256(content).hexdigest()}")
        self.write(self.bundle / "manifest.properties", ("\n".join(manifest) + "\n").encode("ascii"))
        self.write(self.bundle / "PROVENANCE.md", b"provenance")
        self.write(PREPARE.java_executable(self.java), b"java executable")
        self.write(self.java / "release", b'JAVA_VERSION="21.0.11"\n')
        self.write(self.java / "lib" / "modules", b"module image")
        self.write(self.java / "lib" / "server" / "libjvm.dylib", b"JVM library")
        self.write(self.java / "conf" / "security" / "java.security", b"runtime configuration")
        self.probe = mock.patch.object(
            PREPARE.subprocess, "run",
            return_value=subprocess.CompletedProcess([], 0, "", 'openjdk version "21.0.11" 2026-04-21\n'),
        ).start()
        self.addCleanup(mock.patch.stopall)

    def write(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def write_jar(self, path, entry):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(entry, b"class bytecode")

    def prepare(self):
        PREPARE.prepare(self.helper, self.bundle, self.java, self.resources)

    def snapshot(self, root):
        return {str(path.relative_to(root)): path.read_bytes() for path in root.rglob("*") if path.is_file()}

    def existing_resources(self):
        for name in PREPARE.TARGETS:
            self.write(self.resources / name / "old payload", name.encode("ascii"))
        self.write(self.resources / "unrelated resource", b"keep me")
        return self.snapshot(self.resources)

    def test_happy_path_copies_all_inputs_and_normalizes_java_home(self):
        original = [self.snapshot(source) for source in (self.helper, self.bundle, self.java)]
        PREPARE.prepare(self.helper, self.bundle, self.java.parent.parent, self.resources)
        for name, source, before in zip(PREPARE.TARGETS, (self.helper, self.bundle, self.java), original):
            self.assertEqual(before, self.snapshot(self.resources / name))
            self.assertEqual(before, self.snapshot(source))
        self.assertTrue(PREPARE.java_executable(self.resources / "java-runtime").is_file())
        self.assertEqual(set(PREPARE.TARGETS), {path.name for path in self.resources.iterdir()})

    def test_missing_helper_is_rejected_and_preserves_existing_resources(self):
        before = self.existing_resources()
        with self.assertRaisesRegex(ValueError, "Missing or empty"):
            PREPARE.prepare(self.root / "missing helper", self.bundle, self.java, self.resources)
        self.assertEqual(before, self.snapshot(self.resources))

    def test_missing_helper_lib_is_rejected(self):
        for jar in (self.helper / "lib").iterdir():
            jar.unlink()
        with self.assertRaisesRegex(ValueError, "no lib/\\*.jar"):
            self.prepare()

    def test_missing_main_class_is_rejected(self):
        self.write_jar(self.jar, "Other.class")
        with self.assertRaisesRegex(ValueError, "missing .*ComposeHeadlessMainKt"):
            self.prepare()
        self.assertFalse(self.resources.exists())

    def test_invalid_companion_jar_is_rejected(self):
        (self.helper / "lib" / "dependency.jar").write_bytes(b"not a jar")
        with self.assertRaises(zipfile.BadZipFile):
            self.prepare()

    def test_digest_mismatch_preserves_existing_resources(self):
        before = self.existing_resources()
        (self.bundle / "lib_ui_inspector_service.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            self.prepare()
        self.assertEqual(before, self.snapshot(self.resources))

    def test_missing_digest_and_missing_agent_are_rejected(self):
        manifest = self.bundle / "manifest.properties"
        original = manifest.read_text()
        manifest.write_text(original.replace("service.sha256=", "unknown.sha256="))
        with self.assertRaisesRegex(ValueError, "invalid agent bundle digest"):
            self.prepare()
        manifest.write_text(original)
        (self.bundle / PREPARE.ARTIFACTS["agent.arm64-v8a.sha256"]).unlink()
        with self.assertRaisesRegex(ValueError, "Missing or empty"):
            self.prepare()

    def test_old_or_unrecognized_java_preserves_existing_resources(self):
        before = self.existing_resources()
        for output in ('java version "1.8.0_401"', 'openjdk version "11.0.1"', "not Java"):
            with self.subTest(output=output):
                self.probe.return_value = subprocess.CompletedProcess([], 0, "", output)
                with self.assertRaisesRegex(ValueError, "Java 17\\+"):
                    self.prepare()
                self.assertEqual(before, self.snapshot(self.resources))

    def test_java_17_version_on_stdout_is_accepted(self):
        self.probe.return_value = subprocess.CompletedProcess([], 0, 'openjdk version "17.0.12"', "")
        PREPARE.validate_java(self.java)

    def test_java_probe_failure_preserves_existing_resources(self):
        before = self.existing_resources()
        self.probe.side_effect = subprocess.CalledProcessError(1, ["java", "-version"])
        with self.assertRaises(subprocess.CalledProcessError):
            self.prepare()
        self.assertEqual(before, self.snapshot(self.resources))

    def test_missing_java_runtime_files_are_rejected(self):
        for relative in ("release", "lib/modules", "lib/server/libjvm.dylib"):
            with self.subTest(relative=relative):
                path = self.java / relative
                content = path.read_bytes()
                path.unlink()
                with self.assertRaises(ValueError):
                    self.prepare()
                path.write_bytes(content)
        self.assertFalse(self.resources.exists())

    def test_missing_java_executable_is_rejected(self):
        PREPARE.java_executable(self.java).unlink()
        with self.assertRaisesRegex(ValueError, "no bin/java"):
            self.prepare()

    def test_spaces_are_passed_as_single_subprocess_argument_without_shell(self):
        self.prepare()
        self.assertEqual(2, self.probe.call_count)
        for call in self.probe.call_args_list:
            command = call.args[0]
            self.assertEqual(2, len(command))
            self.assertEqual("-version", command[1])
            self.assertIn(" ", command[0])
            self.assertNotIn("shell", call.kwargs)
            self.assertTrue(call.kwargs["check"])
            self.assertEqual(15, call.kwargs["timeout"])

    def test_partial_copy_failure_leaves_all_existing_targets_intact(self):
        before = self.existing_resources()
        copytree = PREPARE.shutil.copytree

        def fail_java_copy(source, target, *args, **kwargs):
            if Path(source).resolve() == self.java.resolve():
                self.write(Path(target) / "partial", b"partial copy")
                raise OSError("copy failed")
            return copytree(source, target, *args, **kwargs)

        with mock.patch.object(PREPARE.shutil, "copytree", side_effect=fail_java_copy):
            with self.assertRaisesRegex(OSError, "copy failed"):
                self.prepare()
        self.assertEqual(before, self.snapshot(self.resources))
        self.assertFalse(list(self.resources.glob(".compose-runtime-*")))

    def test_replacement_failure_rolls_back_previously_installed_targets(self):
        before = self.existing_resources()
        replace = Path.replace

        def fail_bundle_install(source, target):
            if source.name == "compose-agent-bundle" and source.parent.name.startswith(".compose-runtime-"):
                raise OSError("replace failed")
            return replace(source, target)

        with mock.patch.object(Path, "replace", fail_bundle_install):
            with self.assertRaisesRegex(OSError, "replace failed"):
                self.prepare()
        self.assertEqual(before, self.snapshot(self.resources))

    def test_staged_java_probe_failure_leaves_existing_targets_intact(self):
        before = self.existing_resources()
        self.probe.side_effect = [self.probe.return_value, subprocess.CalledProcessError(1, ["java", "-version"])]
        with self.assertRaises(subprocess.CalledProcessError):
            self.prepare()
        self.assertEqual(before, self.snapshot(self.resources))

    def test_successful_update_preserves_unrelated_resources(self):
        self.existing_resources()
        self.prepare()
        self.assertEqual(b"keep me", (self.resources / "unrelated resource").read_bytes())
        for name in PREPARE.TARGETS:
            self.assertFalse((self.resources / name / "old payload").exists())

    def test_output_overlapping_a_source_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "must not overlap"):
            PREPARE.prepare(self.helper, self.bundle, self.java, self.helper / "resources")

    def test_cli_requires_all_four_arguments(self):
        for missing in ("--helper-dist", "--agent-bundle", "--java-home", "--resources"):
            arguments = []
            for flag, value in zip(
                ("--helper-dist", "--agent-bundle", "--java-home", "--resources"),
                (self.helper, self.bundle, self.java, self.resources),
            ):
                if flag != missing:
                    arguments.extend((flag, str(value)))
            with self.subTest(missing=missing), mock.patch("sys.argv", ["prepare-compose-runtime.py", *arguments]):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    PREPARE.main()
                self.assertEqual(2, error.exception.code)

    def test_cli_reports_success_and_validation_errors(self):
        arguments = [
            "prepare-compose-runtime.py", "--helper-dist", str(self.helper),
            "--agent-bundle", str(self.bundle), "--java-home", str(self.java),
            "--resources", str(self.resources),
        ]
        with mock.patch("sys.argv", arguments), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, PREPARE.main())
        self.assertIn("Compose runtime prepared:", output.getvalue())
        before = self.snapshot(self.resources)
        self.write_jar(self.jar, "Other.class")
        with mock.patch("sys.argv", arguments), contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(1, PREPARE.main())
        self.assertIn("Compose runtime preparation failed:", error.getvalue())
        self.assertEqual(before, self.snapshot(self.resources))


if __name__ == "__main__":
    unittest.main()
