"""Installed-payload checks only; no package manager, application, or GUI launch.

Run: python3 -B -m unittest discover -s scripts/tests -p test_linux_installed.py -v
Most cases stub host identity, readelf, and ldd; native cases need binutils.
"""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linux/verify-installed.sh"
LIB = Path("usr/lib/aps-desktop")
APP = LIB / "aps-app"
WORKER = LIB / "aps-memory-worker"
TOOL = LIB / "resources/perfetto-tools/trace_processor_shell"
BINARIES = (APP, WORKER, TOOL)
ELF_HEADER = """\
ELF Header:
  Type:                              EXEC (Executable file)
  Entry point address:               0x401000
  Number of program headers:         2

Program Headers:
  Type           Offset             VirtAddr           PhysAddr
  LOAD           0x0000000000000000 0x0000000000400000 0x0000000000400000
                 0x0000000000001000 0x0000000000001000  R E    0x1000
"""
STATIC_ELF = ELF_HEADER + "\nThere is no dynamic section in this file.\n"
DYNAMIC_ELF = ELF_HEADER + """\
  INTERP         0x0000000000000238 0x0000000000400238 0x0000000000400238
                 0x000000000000001c 0x000000000000001c  R      0x1
  DYNAMIC        0x0000000000000c00 0x0000000000400c00 0x0000000000400c00

Dynamic section at offset 0xc00 contains 2 entries:
  Tag        Type                         Name/Value
 0x0000000000000001 (NEEDED)             Shared library: [libc.so.6]
 0x0000000000000000 (NULL)               0x0
"""


class LinuxInstalledTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="APS installed with spaces ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.stage = self.root / "staged package"
        self.prefix = self.root / "installed prefix"
        self.stubs = self.root / "command stubs"
        self.stubs.mkdir()
        self.log = self.root / "commands.log"
        self.environment = dict(os.environ, PATH=str(self.stubs) + os.pathsep + os.environ["PATH"],
                                APS_TEST_LOG=str(self.log), APS_TEST_OS="Linux", APS_TEST_UID="1000",
                                APS_TEST_DYNAMIC_ELF=DYNAMIC_ELF)
        self.write(self.stage / APP, '#!/bin/sh\nprintf "APP LAUNCHED\\n" >> "$APS_TEST_LOG"\nexit 99\n', 0o755)
        self.write(self.stage / WORKER, '#!/bin/sh\nprintf "WORKER LAUNCHED\\n" >> "$APS_TEST_LOG"\nexit 99\n', 0o755)
        self.write(self.stage / TOOL, """\
            #!/bin/sh
            printf 'version:%s:%s\\n' "$0" "$*" >> "$APS_TEST_LOG"
            [ "$#" -eq 1 ] && [ "$1" = --version ] || exit 98
            printf 'Trace Processor fixture v59.1\\n'
            exit "${APS_TEST_VERSION_EXIT:-0}"
            """, 0o755)
        self.write(self.stage / LIB / "resources/firefox-profiler/index.html", "fixture index\n")
        self.write(self.stage / "usr/share/applications/aps.desktop", "[Desktop Entry]\nExec=/usr/bin/aps-app\n")
        self.write(self.stage / "usr/share/icons/aps.png", b"\x89PNG\x00fixture")
        self.write(self.stage / "usr/share/doc/aps-desktop/empty", b"")
        self.write(self.stage / "DEBIAN/control", "not installed\n")
        (self.stage / "usr/bin").mkdir()
        for binary in (APP, WORKER):
            (self.stage / "usr/bin" / binary.name).symlink_to("../lib/aps-desktop/" + binary.name)
        shutil.copytree(self.stage / "usr", self.prefix / "usr", symlinks=True)
        self.expected = hashlib.sha256((self.stage / TOOL).read_bytes()).hexdigest()
        self.stub("uname", 'printf "%s\\n" "$APS_TEST_OS"\n')
        self.stub("id", 'printf "%s\\n" "$APS_TEST_UID"\n')
        self.stub("readelf", """\
            [ "$#" -eq 4 ] && [ "$1" = --file-header ] &&
                [ "$2" = --program-headers ] && [ "$3" = --dynamic ] || exit 96
            printf 'readelf:%s\\n' "$4" >> "$APS_TEST_LOG"
            if [ "${APS_TEST_READELF_TARGET:-}" = "$4" ]; then
                printf '%s' "${APS_TEST_READELF_OUTPUT-}"
                printf '%s' "${APS_TEST_READELF_STDERR-}" >&2
                exit "${APS_TEST_READELF_EXIT:-0}"
            fi
            printf '%s' "$APS_TEST_DYNAMIC_ELF"
            """)
        self.stub("ldd", """\
            printf 'ldd:%s\\n' "$*" >> "$APS_TEST_LOG"
            if [ "${APS_TEST_LDD_TARGET:-}" = "$1" ]; then
                printf '%s\\n' "${APS_TEST_LDD_OUTPUT:-loader failed}" >&2
                exit "${APS_TEST_LDD_EXIT:-0}"
            fi
            printf 'libc.so.6 => /lib/libc.so.6 (0x1234)\\n'
            """)
        self.real_timeout = shutil.which("timeout")
        self.stub("timeout", """\
            printf 'timeout:%s:%s:%s\\n' "$1" "$2" "$3" >> "$APS_TEST_LOG"
            [ "$1" = --kill-after=5s ] && [ "$2" = 15s ] || exit 97
            shift 2
            if [ "${APS_TEST_TIMEOUT_TARGET:-}" = "$1" ]; then
                exit 124
            fi
            exec "$@"
            """)

    def write(self, path, content, mode=0o644):
        path.parent.mkdir(parents=True, exist_ok=True)
        content = textwrap.dedent(content).encode() if isinstance(content, str) else content
        path.write_bytes(content)
        path.chmod(mode)

    def stub(self, name, body):
        self.write(self.stubs / name, "#!/bin/sh\n" + textwrap.dedent(body), 0o755)

    def run_verifier(self, arguments=None, **environment):
        arguments = [str(self.stage), str(self.prefix), self.expected] if arguments is None else arguments
        return subprocess.run(["bash", str(SCRIPT), *arguments], cwd=self.root,
                              env=dict(self.environment, **environment), text=True,
                              capture_output=True, timeout=8)

    def events(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def assert_rejected(self, result, message):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(message.lower(), result.stderr.lower(), result.stdout + result.stderr)

    def assert_not_executed(self):
        self.assertEqual([], self.events())

    def test_complete_payload_with_spaces_passes_without_launching_app_or_worker(self):
        result = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        events = self.events()
        self.assertEqual(["ldd:" + str(self.prefix / path) for path in BINARIES],
                         [event for event in events if event.startswith("ldd:")])
        self.assertEqual(["version:" + str(self.prefix / TOOL) + ":--version"],
                         [event for event in events if event.startswith("version:")])
        self.assertEqual(["readelf:" + str(self.prefix / path) for path in BINARIES],
                         [event for event in events if event.startswith("readelf:")])
        self.assertEqual(7, sum(event.startswith("timeout:") for event in events))
        self.assertNotIn("LAUNCHED", "\n".join(events))

    def test_argument_count_is_exact(self):
        for arguments in ([], [str(self.stage)], [str(self.stage), str(self.prefix)],
                          [str(self.stage), str(self.prefix), self.expected, "extra"]):
            with self.subTest(arguments=arguments):
                self.assert_rejected(self.run_verifier(arguments), "usage")
        self.assert_not_executed()

    def test_hash_must_be_exactly_64_hex_digits(self):
        for checksum in ("", "a" * 63, "a" * 65, "z" * 64, " " + self.expected,
                         self.expected + "\n", "--version"):
            with self.subTest(checksum=checksum):
                self.assert_rejected(self.run_verifier([str(self.stage), str(self.prefix), checksum]), "sha256")
        self.assert_not_executed()

    def test_uppercase_hash_is_accepted(self):
        result = self.run_verifier([str(self.stage), str(self.prefix), self.expected.upper()])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_requires_linux(self):
        self.assert_rejected(self.run_verifier(APS_TEST_OS="Darwin"), "linux")
        self.assert_not_executed()

    def test_requires_an_ordinary_user(self):
        self.assert_rejected(self.run_verifier(APS_TEST_UID="0"), "non-root")
        self.assert_not_executed()

    def test_identity_must_be_a_nonzero_numeric_uid(self):
        for uid in ("", "unknown", "00", "1000\n0"):
            with self.subTest(uid=uid):
                self.assert_rejected(self.run_verifier(APS_TEST_UID=uid), "non-root")
        self.assert_not_executed()

    def test_failed_identity_lookup_is_rejected(self):
        self.stub("id", "printf '1000\\n'\nexit 1\n")
        self.assert_rejected(self.run_verifier(), "non-root")
        self.assert_not_executed()

    def test_roots_must_be_existing_nonempty_directories(self):
        regular = self.root / "not a directory"
        regular.write_text("fixture")
        for index in (0, 1):
            for value in ("", str(self.root / "missing"), str(regular)):
                with self.subTest(index=index, value=value):
                    arguments = [str(self.stage), str(self.prefix), self.expected]
                    arguments[index] = value
                    self.assert_rejected(self.run_verifier(arguments), "directory")
        self.assert_not_executed()

    def test_stage_must_contain_usr(self):
        shutil.rmtree(self.stage / "usr")
        self.assert_rejected(self.run_verifier(), "usr")
        self.assert_not_executed()

    def test_empty_stage_cannot_pass(self):
        shutil.rmtree(self.stage / "usr")
        (self.stage / "usr").mkdir()
        self.assert_rejected(self.run_verifier(), "staged")
        self.assert_not_executed()

    def test_required_binaries_must_be_in_stage(self):
        for path in BINARIES:
            with self.subTest(path=path):
                staged = self.stage / path
                content = staged.read_bytes()
                staged.unlink()
                self.assert_rejected(self.run_verifier(), str(path))
                self.write(staged, content, 0o755)
        self.assert_not_executed()

    def test_all_staged_regular_files_are_compared(self):
        for staged in sorted((self.stage / "usr").rglob("*")):
            if not staged.is_file() or staged.is_symlink():
                continue
            relative = staged.relative_to(self.stage)
            with self.subTest(path=relative):
                installed = self.prefix / relative
                content = installed.read_bytes()
                installed.write_bytes(content + b"corruption")
                self.assert_rejected(self.run_verifier(), str(relative))
                installed.write_bytes(content)
        self.assert_not_executed()

    def test_missing_installed_file_fails(self):
        relative = Path("usr/share/applications/aps.desktop")
        (self.prefix / relative).unlink()
        self.assert_rejected(self.run_verifier(), str(relative))
        self.assert_not_executed()

    def test_installed_regular_file_cannot_be_a_directory_or_symlink(self):
        relative = Path("usr/share/applications/aps.desktop")
        installed = self.prefix / relative
        installed.unlink()
        installed.mkdir()
        self.assert_rejected(self.run_verifier(), str(relative))
        installed.rmdir()
        installed.symlink_to(self.stage / relative)
        self.assert_rejected(self.run_verifier(), str(relative))
        self.assert_not_executed()

    def test_newline_backslash_and_dash_filenames_are_compared(self):
        relative = Path("usr/share/-asset with\nnewline\\and spaces")
        for root in (self.stage, self.prefix):
            self.write(root / relative, "fixture\n")
        result = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        (self.prefix / relative).write_text("changed\n")
        self.assert_rejected(self.run_verifier(), "mismatch")

    def test_package_metadata_and_extra_installed_files_are_not_compared(self):
        self.write(self.prefix / "DEBIAN/control", "different metadata\n")
        self.write(self.prefix / "usr/share/unrelated-package", "unrelated\n")
        result = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_relative_roots_and_trailing_slashes_are_supported(self):
        result = self.run_verifier([self.stage.name + "/", self.prefix.name + "/", self.expected])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_app_and_worker_links_must_be_staged_and_installed(self):
        for root in (self.stage, self.prefix):
            for path in (APP, WORKER):
                link = root / "usr/bin" / path.name
                target = os.readlink(link)
                with self.subTest(root=root, binary=path):
                    link.unlink()
                    self.assert_rejected(self.run_verifier(), "symlink")
                    link.write_bytes((root / path).read_bytes())
                    link.chmod(0o755)
                    self.assert_rejected(self.run_verifier(), "symlink")
                    link.unlink()
                    link.symlink_to(target)
        self.assert_not_executed()

    def test_app_and_worker_links_must_point_to_packaged_binaries(self):
        for root in (self.stage, self.prefix):
            for path in (APP, WORKER):
                link = root / "usr/bin" / path.name
                target = os.readlink(link)
                for bad in ("missing", "../lib/aps-desktop/" + (WORKER.name if path == APP else APP.name)):
                    with self.subTest(root=root, binary=path, target=bad):
                        link.unlink()
                        link.symlink_to(bad)
                        self.assert_rejected(self.run_verifier(), "symlink")
                link.unlink()
                link.symlink_to(target)
        self.assert_not_executed()

    def test_installed_binaries_must_be_executable(self):
        for path in BINARIES:
            with self.subTest(binary=path):
                binary = self.prefix / path
                binary.chmod(0o644)
                self.assert_rejected(self.run_verifier(), "executable")
                binary.chmod(0o755)
        self.assert_not_executed()

    @unittest.skipIf(os.geteuid() == 0, "root bypasses ordinary-user readability checks")
    def test_trace_processor_must_be_readable(self):
        (self.prefix / TOOL).chmod(0o111)
        self.assert_rejected(self.run_verifier(), "readable")
        self.assert_not_executed()

    @unittest.skipIf(os.geteuid() == 0, "root bypasses directory traversal permissions")
    def test_unreadable_staged_subtree_cannot_be_silently_skipped(self):
        directory = self.stage / "usr/share/private"
        self.write(directory / "hidden", "not installed\n")
        directory.chmod(0)
        self.addCleanup(directory.chmod, 0o755)
        self.assert_rejected(self.run_verifier(), "staged")
        self.assert_not_executed()

    def test_matching_staged_and_installed_tool_still_requires_external_pin(self):
        malicious = '#!/bin/sh\nprintf "UNTRUSTED EXECUTED\\n" >> "$APS_TEST_LOG"\n'
        for root in (self.stage, self.prefix):
            self.write(root / TOOL, malicious, 0o755)
        self.assert_rejected(self.run_verifier(), "sha256")
        self.assert_not_executed()

    def test_invalid_pin_never_invokes_ldd_or_version(self):
        self.assert_rejected(self.run_verifier([str(self.stage), str(self.prefix), "0" * 64]), "sha256")
        self.assert_not_executed()

    def test_tool_version_nonzero_fails(self):
        self.assert_rejected(self.run_verifier(APS_TEST_VERSION_EXIT="42"), "version")

    def test_tool_version_timeout_fails(self):
        self.assert_rejected(self.run_verifier(APS_TEST_TIMEOUT_TARGET=str(self.prefix / TOOL)), "version")

    def test_ldd_nonzero_and_missing_libraries_fail_for_each_binary(self):
        for path in BINARIES:
            for status, output in (("7", "loader failed"), ("0", "libmissing.so => not found"),
                                   ("1", "libmissing.so => not found")):
                with self.subTest(binary=path, status=status, output=output):
                    result = self.run_verifier(APS_TEST_LDD_TARGET=str(self.prefix / path),
                                               APS_TEST_LDD_EXIT=status, APS_TEST_LDD_OUTPUT=output)
                    self.assert_rejected(result, "ldd")
                    self.assertIn(str(self.prefix / path), result.stderr)

    def test_ldd_timeout_fails(self):
        self.assert_rejected(self.run_verifier(APS_TEST_TIMEOUT_TARGET="ldd"), "ldd")

    def test_proven_static_exec_and_pie_skip_ldd_for_each_binary(self):
        pie = STATIC_ELF.replace("EXEC (Executable file)", "DYN (Position-Independent Executable file)")
        pie = pie.replace("There is no dynamic section in this file.", """\
  DYNAMIC        0x0000000000000c00 0x0000000000400c00 0x0000000000400c00
Dynamic section at offset 0xc00 contains 1 entry:
  Tag        Type                         Name/Value
 0x0000000000000000 (NULL)               0x0""")
        for path in BINARIES:
            for output in (STATIC_ELF, pie):
                with self.subTest(binary=path, elf_type=output.split("Type:", 1)[1].splitlines()[0]):
                    self.log.unlink(missing_ok=True)
                    result = self.run_verifier(APS_TEST_READELF_TARGET=str(self.prefix / path),
                                               APS_TEST_READELF_OUTPUT=output,
                                               APS_TEST_LDD_TARGET=str(self.prefix / path),
                                               APS_TEST_LDD_EXIT="1",
                                               APS_TEST_LDD_OUTPUT="not a dynamic executable")
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn("readelf:" + str(self.prefix / path), self.events())
                    self.assertNotIn("ldd:" + str(self.prefix / path), self.events())
                    self.assertEqual(1, sum(event.startswith("version:") for event in self.events()))

    def test_interp_or_needed_alone_still_requires_ldd(self):
        interp_only = STATIC_ELF.replace("Program Headers:\n", "Program Headers:\n  INTERP 0x238\n")
        needed_only = DYNAMIC_ELF.replace("  INTERP", "  NOTE  ")
        for output in (interp_only, needed_only):
            for status, message in (("1", "not a dynamic executable"), ("0", "libmissing.so => not found")):
                with self.subTest(output=output, status=status):
                    result = self.run_verifier(APS_TEST_READELF_TARGET=str(self.prefix / TOOL),
                                               APS_TEST_READELF_OUTPUT=output,
                                               APS_TEST_LDD_TARGET=str(self.prefix / TOOL),
                                               APS_TEST_LDD_EXIT=status, APS_TEST_LDD_OUTPUT=message)
                    self.assert_rejected(result, "ldd")

    def test_readelf_failure_or_diagnostics_are_fatal_even_with_static_stdout(self):
        for path in BINARIES:
            for status, diagnostic in (("1", "readelf: Error: corrupt ELF\n"), ("1", ""),
                                       ("0", "readelf: Warning: truncated program headers\n")):
                with self.subTest(binary=path, status=status, diagnostic=diagnostic):
                    self.log.unlink(missing_ok=True)
                    result = self.run_verifier(APS_TEST_READELF_TARGET=str(self.prefix / path),
                                               APS_TEST_READELF_OUTPUT=STATIC_ELF,
                                               APS_TEST_READELF_STDERR=diagnostic,
                                               APS_TEST_READELF_EXIT=status)
                    self.assert_rejected(result, "readelf")
                    self.assertIn(str(self.prefix / path), result.stderr)
                    self.assertNotIn("ldd:" + str(self.prefix / path), self.events())
                    self.assertFalse(any(event.startswith("version:") for event in self.events()))

    def test_readelf_output_must_prove_a_complete_executable_elf(self):
        malformed = ("", "not ELF\n", ELF_HEADER,
                     STATIC_ELF.replace("EXEC (Executable file)", "REL (Relocatable file)"),
                     STATIC_ELF.replace("EXEC (Executable file)", "CORE (Core file)"),
                     STATIC_ELF.replace("0x401000", "0x0"),
                     STATIC_ELF.replace("  LOAD ", "  NOTE "),
                     "File: archive.a(member)\n" + STATIC_ELF,
                     STATIC_ELF.replace("There is no dynamic section in this file.",
                                        "Dynamic section at offset 0xc00 contains 1 entry:"),
                     STATIC_ELF.replace("There is no dynamic section in this file.",
                                        "  DYNAMIC 0xc00\nThere is no dynamic section in this file."))
        for output in malformed:
            with self.subTest(output=output):
                self.log.unlink(missing_ok=True)
                result = self.run_verifier(APS_TEST_READELF_TARGET=str(self.prefix / TOOL),
                                           APS_TEST_READELF_OUTPUT=output)
                self.assert_rejected(result, "readelf")
                self.assertNotIn("ldd:" + str(self.prefix / TOOL), self.events())
                self.assertFalse(any(event.startswith("version:") for event in self.events()))

    def test_readelf_timeout_is_fatal(self):
        result = self.run_verifier(APS_TEST_TIMEOUT_TARGET="readelf")
        self.assert_rejected(result, "readelf")
        self.assertFalse(any(event.startswith(("ldd:", "version:")) for event in self.events()))

    def test_readelf_command_is_required(self):
        commands = self.root / "without readelf"
        commands.mkdir()
        for name in ("bash", "uname", "id", "cmp", "find", "mktemp", "readlink", "rm", "sha256sum",
                     "timeout", "tr", "ldd"):
            executable = shutil.which(name, path=self.environment["PATH"])
            self.assertIsNotNone(executable, name)
            (commands / name).symlink_to(executable)
        self.assert_rejected(self.run_verifier(PATH=str(commands)), "readelf")
        self.assert_not_executed()

    @unittest.skipUnless(sys.platform.startswith("linux") and os.geteuid() != 0,
                         "real ELF/coreutils verification requires ordinary-user Linux")
    def test_real_linux_tools_as_ordinary_user(self):
        for name in ("uname", "id", "timeout", "readelf", "ldd"):
            (self.stubs / name).unlink()
        system_binary = shutil.which("true")
        self.assertIsNotNone(system_binary)
        for root in (self.stage, self.prefix):
            for path in BINARIES:
                shutil.copyfile(system_binary, root / path)
                (root / path).chmod(0o755)
        self.expected = hashlib.sha256((self.stage / TOOL).read_bytes()).hexdigest()
        result = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("true (GNU coreutils)", result.stdout)

    @unittest.skipUnless(sys.platform.startswith("linux") and os.geteuid() != 0 and shutil.which("gcc"),
                         "real static ELF verification requires ordinary-user Linux and gcc")
    def test_real_static_exec_and_pie_are_accepted_without_ldd(self):
        for name in ("uname", "id", "timeout", "readelf"):
            (self.stubs / name).unlink()
        dynamic = shutil.which("true")
        self.assertIsNotNone(dynamic)
        for root in (self.stage, self.prefix):
            for path in (APP, WORKER):
                shutil.copyfile(dynamic, root / path)
                (root / path).chmod(0o755)
        source = self.root / "static.c"
        source.write_text('#include <stdio.h>\nint main(void) { puts("static ELF fixture"); return 0; }\n')
        binary = self.root / "static-tool"
        for option in ("-static", "-static-pie"):
            with self.subTest(option=option):
                compiled = subprocess.run(["gcc", option, str(source), "-o", str(binary)], text=True,
                                          capture_output=True, timeout=30)
                self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
                for root in (self.stage, self.prefix):
                    shutil.copyfile(binary, root / TOOL)
                    (root / TOOL).chmod(0o755)
                self.expected = hashlib.sha256(binary.read_bytes()).hexdigest()
                self.log.unlink(missing_ok=True)
                result = self.run_verifier(APS_TEST_LDD_TARGET=str(self.prefix / TOOL),
                                           APS_TEST_LDD_EXIT="1", APS_TEST_LDD_OUTPUT="not a dynamic executable")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("static ELF fixture", result.stdout)
                self.assertNotIn("ldd:" + str(self.prefix / TOOL), self.events())

    @unittest.skipUnless(sys.platform.startswith("linux") and os.geteuid() != 0,
                         "real malformed ELF inspection requires ordinary-user Linux")
    def test_real_malformed_elf_is_rejected_before_execution(self):
        for name in ("uname", "id", "timeout", "readelf"):
            (self.stubs / name).unlink()
        dynamic = shutil.which("true")
        self.assertIsNotNone(dynamic)
        for root in (self.stage, self.prefix):
            for path in (APP, WORKER):
                shutil.copyfile(dynamic, root / path)
                (root / path).chmod(0o755)
            self.write(root / TOOL, b"\x7fELF\x02\x01\x01\x00truncated", 0o755)
        self.expected = hashlib.sha256((self.stage / TOOL).read_bytes()).hexdigest()
        result = self.run_verifier()
        self.assert_rejected(result, "readelf")
        self.assertNotIn("ldd:" + str(self.prefix / TOOL), self.events())

    @unittest.skipUnless(sys.platform.startswith("linux") and os.geteuid() == 0,
                         "real root rejection requires Linux root")
    def test_real_linux_root_is_rejected(self):
        for name in ("uname", "id"):
            (self.stubs / name).unlink()
        self.assert_rejected(self.run_verifier(), "non-root")
        self.assert_not_executed()

    @unittest.skipUnless(shutil.which("timeout"), "real timeout requires coreutils")
    def test_real_timeout_terminates_a_hung_version_command(self):
        self.stub("timeout", """\
            [ "$1" = --kill-after=5s ] && [ "$2" = 15s ] || exit 97
            shift 2
            exec "$APS_TEST_REAL_TIMEOUT" --kill-after=0.1s 0.1s "$@"
            """)
        for root in (self.stage, self.prefix):
            self.write(root / TOOL, '#!/bin/sh\nexec sleep 10\n', 0o755)
        self.expected = hashlib.sha256((self.stage / TOOL).read_bytes()).hexdigest()
        self.assert_rejected(self.run_verifier(APS_TEST_REAL_TIMEOUT=self.real_timeout), "version")


if __name__ == "__main__":
    unittest.main()
