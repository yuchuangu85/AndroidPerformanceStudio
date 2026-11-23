"""Linux-only release packaging contracts, using the standard library."""

from pathlib import Path
import hashlib
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/aps-desktop-release.yml"


def job(source, name):
    return re.split(r"\n  (?=\S)", source.split("\n  " + name + ":\n", 1)[1], maxsplit=1)[0]


class LinuxPackagingTest(unittest.TestCase):
    def setUp(self):
        self.linux = job(WORKFLOW.read_text(), "linux")

    def test_trace_processor_is_readable_before_deb_assembly(self):
        permission = 'chmod 0755 "$libdir/resources/perfetto-tools/trace_processor_shell"'
        self.assertIn(permission, self.linux)
        self.assertLess(self.linux.index(permission), self.linux.index("dpkg-deb --build"))

    def test_deb_dependencies_come_from_all_packaged_elf_binaries(self):
        self.assertIn("dpkg-dev", self.linux)
        self.assertIn("dpkg-shlibdeps -O", self.linux)
        scan = self.linux.split("dpkg-shlibdeps -O", 1)[1].split(")", 1)[0]
        for binary in ("aps-app", "aps-memory-worker", "resources/perfetto-tools/trace_processor_shell"):
            self.assertIn(f'"$libdir/{binary}"', scan)
        control = self.linux.split('cat > "$root/DEBIAN/control" <<EOF', 1)[1].split("EOF", 1)[0]
        self.assertIn("Depends: ${shlib_dependencies}", control)
        for package in ("libvulkan1", "libegl1", "libwayland-client0", "curl", "xdg-utils"):
            self.assertIn(package, control)
        self.assertNotIn("--ignore-missing-info", self.linux)

    def test_rpm_declares_dynamically_loaded_graphics_and_command_requirements(self):
        spec = self.linux.split("cat > dist/rpm/SPECS/aps-desktop.spec <<EOF", 1)[1].split("EOF", 1)[0]
        for requirement in ("libvulkan.so.1()(64bit)", "libEGL.so.1()(64bit)",
                            "libwayland-client.so.0()(64bit)", "curl", "xdg-utils"):
            self.assertIn(requirement, spec)
        self.assertNotIn("AutoReqProv: no", spec)

    def test_both_formats_are_installed_and_verified_before_upload(self):
        self.assertIn('sudo apt-get install -y "$deb"', self.linux)
        self.assertIn("scripts/linux/verify-installed.sh", self.linux)
        self.assertIn("fedora:44", self.linux)
        self.assertIn("dnf install", self.linux)
        self.assertIn("runuser -u aps-smoke", self.linux)
        self.assertIn("sudo apt-get remove -y aps-desktop", self.linux)
        self.assertIn("dnf remove", self.linux)
        self.assertLess(self.linux.index("scripts/linux/verify-installed.sh"),
                        self.linux.index("name: Upload Linux packages"))
        self.assertNotIn("--nodeps", self.linux)
        self.assertNotIn("--skip-broken", self.linux)

    def test_records_actual_abi_and_rpm_requirements(self):
        self.assertIn("readelf --version-info", self.linux)
        self.assertIn("rpm -qp --requires", self.linux)
        self.assertIn("linux-packaging-evidence-${{ matrix.arch }}", self.linux)
        self.assertIn("if: always()", self.linux)

    def test_does_not_change_common_trace_processor_installation(self):
        installer = (ROOT / "scripts/install-trace-processor.sh").read_text()
        self.assertIn('chmod +x "$native_target"', installer)
        self.assertNotIn('chmod 0755 "$native_target"', installer)


@unittest.skipUnless(sys.platform.startswith("linux") and all(shutil.which(tool) for tool in
                     ("cc", "dpkg-shlibdeps", "dpkg-deb", "rpmbuild", "rpm", "rpm2cpio", "cpio")),
                     "Native Linux compiler and DEB/RPM tools required")
class NativeLinuxPackagingTest(unittest.TestCase):
    def command(self, arguments, **kwargs):
        result = subprocess.run(arguments, text=True, capture_output=True, timeout=90, **kwargs)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout

    def test_real_elf_package_roundtrip_preserves_dependencies_permissions_and_bytes(self):
        for static_tool in (False, True):
            with self.subTest(static_tool=static_tool):
                self.package_roundtrip(static_tool)

    def package_roundtrip(self, static_tool):
        with tempfile.TemporaryDirectory(prefix="aps-linux-package-") as directory:
            root = Path(directory)
            root.chmod(0o755)
            source = root / "sample.c"
            source.write_text('#include <stdio.h>\nint main(void) { puts("Perfetto v59.1-fixture"); return 0; }\n')
            sample = root / "sample"
            self.command(["cc", str(source), "-o", str(sample)])
            tool_sample = root / "sample-tool"
            self.command(["cc", *(["-static"] if static_tool else []), str(source), "-o", str(tool_sample)])
            expected_hash = hashlib.sha256(tool_sample.read_bytes()).hexdigest()
            architecture = self.command(["dpkg", "--print-architecture"]).strip()
            rpm_architecture = {"amd64": "x86_64", "arm64": "aarch64"}[architecture]
            target = "fixture-linux-gnu"
            release = root / "rust-desktop/target" / target / "release"
            release.mkdir(parents=True)
            for binary in ("aps-app", "aps-memory-worker"):
                shutil.copy2(sample, release / binary)
            profiler = root / "dist/firefox-profiler"
            profiler.mkdir(parents=True)
            (profiler / "index.html").write_text("<html>fixture</html>")
            icon = root / "rust-desktop/crates/aps-app/assets/icons/linux/app-icon.png"
            icon.parent.mkdir(parents=True)
            icon.write_bytes(b"fixture icon")
            installer = root / "scripts/install-trace-processor.sh"
            installer.parent.mkdir()
            # Reproduce the downloader's original 0711 mode, without network or
            # substituting the actual packaging commands under test.
            installer.write_text('set -eu\nmkdir -p "$PERFETTO_TOOLS_DIR"\n'
                                 'install -m 0711 sample-tool "$PERFETTO_TOOLS_DIR/trace_processor_shell"\n')
            linux = job(WORKFLOW.read_text(), "linux")
            step = linux.split("      - name: Package deb and rpm\n", 1)[1]
            step = re.split(r"\n      - (?=\S)", step, maxsplit=1)[0]
            script = textwrap.dedent(step.split("        run: |\n", 1)[1])
            environment = dict(os.environ, RELEASE_TAG="aps-desktop-v0.8.2", DEB_ARCH=architecture,
                               RPM_ARCH=rpm_architecture, TARGET=target)
            self.command(["bash", "-euo", "pipefail", "-c", script], cwd=root, env=environment)
            basename = "aps-desktop-aps-desktop-v0.8.2-linux-"
            deb = root / "dist" / (basename + architecture + ".deb")
            rpm = root / "dist" / (basename + rpm_architecture + ".rpm")
            dependencies = self.command(["dpkg-deb", "--field", str(deb), "Depends"])
            for dependency in ("libc6", "libvulkan1", "libegl1", "libwayland-client0", "curl", "xdg-utils"):
                self.assertIn(dependency, dependencies)
            self.assertIn("libc.so.6", self.command(["rpm", "-qp", "--requires", str(rpm)]))
            deb_root = root / "deb-installed"
            self.command(["dpkg-deb", "--extract", str(deb), str(deb_root)])
            rpm_root = root / "rpm-installed"
            rpm_root.mkdir()
            archive = subprocess.run(["rpm2cpio", str(rpm)], capture_output=True, check=True)
            extracted = subprocess.run(["cpio", "-idm", "--quiet"], input=archive.stdout,
                                       cwd=rpm_root, capture_output=True, timeout=30)
            self.assertEqual(0, extracted.returncode, extracted.stderr.decode())
            staged = root / "dist" / (basename + architecture)
            for installed in (deb_root, rpm_root):
                tool = installed / "usr/lib/aps-desktop/resources/perfetto-tools/trace_processor_shell"
                self.assertEqual(0o755, stat.S_IMODE(tool.stat().st_mode))
                self.assertEqual(expected_hash, hashlib.sha256(tool.read_bytes()).hexdigest())
                verifier = ["bash", str(ROOT / "scripts/linux/verify-installed.sh"), str(staged),
                            str(installed), expected_hash]
                if os.geteuid() == 0:
                    verifier = ["runuser", "-u", "nobody", "--", *verifier]
                self.command(verifier)


if __name__ == "__main__":
    unittest.main()
