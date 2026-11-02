import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / 'collect-linux-adb.py'
SPEC = importlib.util.spec_from_file_location('collect_linux_adb', SCRIPT)
collector = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = collector
SPEC.loader.exec_module(collector)


def elf_bytes(machine=183):
    return b'\x7fELF\x02\x01' + bytes(12) + machine.to_bytes(2, 'little') + b'original'


class CollectTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.system = self.root / 'installed'
        self.system.mkdir()
        self.output = self.root / 'source'
        self.docs = self.root / 'doc'
        self.common = self.root / 'common-licenses'
        self.common.mkdir()
        self.files = {}
        self.packages = {}
        for name, package, source in [
            ('adb', 'adb', 'android-platform-tools'),
            ('libadb.so.0', 'android-libadb:arm64', 'android-platform-tools'),
            ('libusb-1.0.so.0', 'libusb-1.0-0:arm64', 'libusb-1.0'),
            ('libprotobuf.so.32', 'libprotobuf32t64:arm64', 'protobuf'),
            ('libstdc++.so.6', 'libstdc++6:arm64', 'gcc-14'),
            ('libgcc_s.so.1', 'libgcc-s1:arm64', 'gcc-14'),
            ('libc.so.6', 'libc6:arm64', 'glibc'),
            ('libm.so.6', 'libc6:arm64', 'glibc'),
            ('ld-linux-aarch64.so.1', 'libc6:arm64', 'glibc'),
        ]:
            path = self.system / name
            path.write_bytes(elf_bytes())
            self.files[name] = path
            self.packages[path] = (package, source)
            doc = self.docs / package.split(':')[0]
            doc.mkdir(parents=True, exist_ok=True)
            (doc / 'copyright').write_text(f'Copyright for {package}\nLicense: test\n')
        self.adb = self.files['adb']
        self.adb.chmod(0o755)
        self.version = 'Android Debug Bridge version 1.0.41\nVersion 34.0.4-1build3\n'
        # Deliberately omit indirect libraries from adb's fixture. The closure
        # walk must discover libusb/protobuf/C++/GCC and terminate despite cycles.
        self.graph = {
            'adb': ['libadb.so.0'],
            'libadb.so.0': ['libusb-1.0.so.0', 'libprotobuf.so.32'],
            'libprotobuf.so.32': ['libstdc++.so.6'],
            'libstdc++.so.6': ['libgcc_s.so.1', 'libadb.so.0'],
        }
        self.ldd_overrides = {}
        self.calls = []
        self.fail_patch = False
        self.fail_command = None
        patches = [
            mock.patch.object(collector, 'check_host'),
            mock.patch.object(collector, 'DOC_ROOT', self.docs),
            mock.patch.object(collector, 'COMMON_LICENSE_ROOT', self.common),
            mock.patch.object(collector.subprocess, 'run', side_effect=self.command),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def command(self, args, **kwargs):
        self.calls.append(args)
        if args[0] == self.fail_command:
            raise subprocess.CalledProcessError(1, args, stderr='mock failure')
        if args == [str(self.adb), 'version']:
            output = self.version
        elif args[0] == 'ldd':
            binary = Path(args[1]).name
            output = self.ldd_overrides.get(binary)
            if output is None:
                output = 'linux-vdso.so.1 (0x1234)\n'
                for name in self.graph.get(binary, []) + ['libc.so.6', 'libm.so.6']:
                    output += f'{name} => {self.files[name]} (0xabcd)\n'
                output += f'{self.files["ld-linux-aarch64.so.1"]} (0xabcd)\n'
        elif args[:2] == ['dpkg-query', '--search']:
            path = Path(args[2])
            if path not in self.packages:
                raise subprocess.CalledProcessError(1, args, stderr='no path found')
            output = f'{self.packages[path][0]}: {path}\n'
        elif args[:2] == ['dpkg-query', '--show']:
            package = args[-1]
            source = next(value[1] for value in self.packages.values() if value[0] == package)
            output = f'{package}\t1:34.0.4-1build3\t{source}\t1:34.0.4-1build3\tarm64\t\n'
        elif args[0] == 'patchelf':
            path = Path(args[-1])
            self.assertTrue(path.is_relative_to(self.root))
            self.assertFalse(path.is_relative_to(self.system))
            path.write_bytes(path.read_bytes() + b'patched')
            if self.fail_patch:
                raise subprocess.CalledProcessError(1, args, stderr='patch failed')
            output = ''
        else:
            self.fail(f'Unexpected external command: {args}')
        self.assertTrue(kwargs['check'])
        self.assertEqual(kwargs['timeout'], collector.COMMAND_TIMEOUT)
        self.assertEqual(kwargs['env']['LC_ALL'], 'C')
        return subprocess.CompletedProcess(args, 0, output, '')

    def collect(self):
        return collector.collect(self.adb, self.output)

    def assert_clean(self):
        self.assertFalse(os.path.lexists(self.output))
        self.assertEqual(list(self.root.glob('.collect-linux-adb-*')), [])

    def test_full_transitive_closure_and_only_copy_is_patched(self):
        originals = {path: (path.read_bytes(), path.stat().st_mode) for path in self.files.values()}
        self.assertEqual(self.collect(), self.output)
        expected = set(self.files) - {'adb', 'libc.so.6', 'libm.so.6', 'ld-linux-aarch64.so.1'}
        self.assertEqual({path.name for path in (self.output / 'lib64').iterdir()}, expected)
        self.assertEqual({path.name for path in self.output.iterdir()},
                         {'adb', 'lib64', 'NOTICE.txt', 'source.properties'})
        self.assertEqual((self.output / 'adb').stat().st_mode & 0o777, 0o755)
        for path, (content, mode) in originals.items():
            self.assertEqual(path.read_bytes(), content)
            self.assertEqual(path.stat().st_mode, mode)
        patch_calls = [args for args in self.calls if args[0] == 'patchelf']
        self.assertEqual(len(patch_calls), len(expected) + 1)
        for args in patch_calls:
            self.assertEqual(args[1], '--set-rpath')
            self.assertEqual(args[2], '$ORIGIN/lib64' if Path(args[-1]).name == 'adb' else '$ORIGIN')
        ldd_paths = [args[1] for args in self.calls if args[0] == 'ldd']
        self.assertEqual(len(ldd_paths), len(set(ldd_paths)))
        self.assertEqual(len(ldd_paths), len(expected) + 1)

    def test_actual_revision_not_protocol_or_hardcoded_37(self):
        self.collect()
        properties = (self.output / 'source.properties').read_text()
        self.assertIn('Pkg.Revision=34.0.4-1build3\n', properties)
        self.assertNotIn('37', properties)
        self.assertNotIn('1.0.41', properties)

    def test_bad_version_fails_closed(self):
        for version in ['Android Debug Bridge version 1.0.41\n', 'Version 37\n',
                        self.version + 'Version 37\n', self.version + 'Version bad/value\n']:
            with self.subTest(version=version):
                self.version = version
                with self.assertRaises(collector.CollectionError):
                    self.collect()
                self.assert_clean()

    def test_notice_covers_every_copied_artifact_and_package(self):
        doc = self.docs / 'libusb-1.0-0' / 'copyright'
        doc.write_text('LGPL details: /usr/share/common-licenses/LGPL-2.1.\n')
        (self.common / 'LGPL-2.1').write_text('Full LGPL test license\n')
        self.collect()
        notice = (self.output / 'NOTICE.txt').read_text()
        for name, path in self.files.items():
            package, source = self.packages[path]
            if source == 'glibc':
                self.assertNotIn(f'Package: {package}\n', notice)
                continue
            self.assertIn(f'File: {"adb" if name == "adb" else "lib64/" + name}\n', notice)
            self.assertIn(f'Installed source: {path}\n', notice)
            self.assertIn(f'Package: {package}\n', notice)
            self.assertIn(f'Source: {source}\n', notice)
            self.assertIn(str(self.docs / package.split(':')[0] / 'copyright'), notice)
        self.assertIn('Version: 1:34.0.4-1build3\n', notice)
        self.assertIn('Source-Version: 1:34.0.4-1build3\n', notice)
        self.assertIn('Ubuntu 24.04', notice)
        self.assertIn('ABI-compatible modified versions', notice)
        self.assertIn('Full LGPL test license', notice)

    def test_missing_transitive_dependency_rejected(self):
        self.ldd_overrides['libprotobuf.so.32'] = 'libstdc++.so.6 => not found\n'
        with self.assertRaisesRegex(collector.CollectionError, 'Missing dependency'):
            self.collect()
        self.assert_clean()

    def test_nonexistent_resolved_library_rejected(self):
        self.files['libusb-1.0.so.0'].unlink()
        with self.assertRaises(OSError):
            self.collect()
        self.assert_clean()

    def test_unknown_relative_or_empty_ldd_rejected(self):
        for output in ['', 'statically linked\n', 'not a dynamic executable\n',
                       'libbad.so => ./libbad.so (0x1234)', 'warning: something\n',
                       'libbad.so => /tmp/../libbad.so (0x1234)',
                       '../escape => /tmp/libbad.so (0x1234)']:
            with self.subTest(output=output):
                self.ldd_overrides['adb'] = output
                with self.assertRaises(collector.CollectionError):
                    self.collect()
                self.assert_clean()

    def test_missing_copyright_rejected_for_adb_and_library(self):
        for package in ('adb', 'libusb-1.0-0'):
            with self.subTest(package=package):
                path = self.docs / package / 'copyright'
                original = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(collector.CollectionError, 'copyright/license unavailable'):
                    self.collect()
                self.assert_clean()
                path.write_bytes(original)

    def test_empty_copyright_and_missing_common_license_rejected(self):
        path = self.docs / 'adb' / 'copyright'
        for text in ('', 'See /usr/share/common-licenses/LGPL-2.1\n'):
            with self.subTest(text=text):
                path.write_text(text)
                with self.assertRaises(collector.CollectionError):
                    self.collect()
                self.assert_clean()

    def test_missing_package_mapping_rejected(self):
        del self.packages[self.files['libusb-1.0.so.0']]
        with self.assertRaisesRegex(collector.CollectionError, 'No dpkg package owns'):
            self.collect()
        self.assert_clean()

    def test_patchelf_failure_cleans_temporary_and_preserves_system(self):
        self.fail_patch = True
        originals = {path: path.read_bytes() for path in self.files.values()}
        with self.assertRaisesRegex(collector.CollectionError, 'patch failed'):
            self.collect()
        self.assert_clean()
        self.assertEqual({path: path.read_bytes() for path in self.files.values()}, originals)

    def test_command_failure_cleans_output(self):
        for command in ('ldd', str(self.adb), 'dpkg-query'):
            with self.subTest(command=command):
                self.fail_command = command
                with self.assertRaises(collector.CollectionError):
                    self.collect()
                self.assert_clean()

    def test_existing_directory_file_and_symlink_are_never_overwritten(self):
        self.output.mkdir()
        sentinel = self.output / 'user-data'
        sentinel.write_text('preserve')
        with self.assertRaisesRegex(collector.CollectionError, 'Refusing to overwrite'):
            self.collect()
        self.assertEqual(sentinel.read_text(), 'preserve')
        sentinel.unlink()
        self.output.rmdir()
        self.output.write_text('user-file')
        with self.assertRaises(collector.CollectionError):
            self.collect()
        self.assertEqual(self.output.read_text(), 'user-file')
        self.output.unlink()
        self.output.symlink_to(self.root / 'missing')
        with self.assertRaises(collector.CollectionError):
            self.collect()
        self.assertTrue(self.output.is_symlink())
        self.assertEqual(self.calls, [])

    def test_output_parent_must_exist_and_not_be_symlink(self):
        for path in (self.root / 'missing' / 'source', self.root / 'alias' / 'source'):
            if path.parent.name == 'alias':
                path.parent.symlink_to(self.system, target_is_directory=True)
            with self.subTest(path=path), self.assertRaises(collector.CollectionError):
                collector.collect(self.adb, path)
        self.assertEqual(self.calls, [])

    def test_wrong_elf_architecture_rejected(self):
        self.adb.write_bytes(elf_bytes(62))
        with self.assertRaisesRegex(collector.CollectionError, 'AArch64'):
            self.collect()
        self.assert_clean()

    def test_library_wrong_architecture_is_rejected(self):
        self.files['libusb-1.0.so.0'].write_bytes(elf_bytes(62))
        with self.assertRaisesRegex(collector.CollectionError, 'AArch64'):
            self.collect()
        self.assert_clean()

    def test_library_symlink_is_copied_using_needed_soname(self):
        soname = self.files['libstdc++.so.6']
        real = self.system / 'libstdc++.so.6.0.33'
        soname.rename(real)
        self.packages[real] = self.packages.pop(soname)
        soname.symlink_to(real.name)
        self.collect()
        bundled = self.output / 'lib64' / soname.name
        self.assertTrue(bundled.is_file())
        self.assertFalse(bundled.is_symlink())
        self.assertFalse((bundled.parent / real.name).exists())
        self.assertEqual(real.read_bytes(), elf_bytes())

    def test_incomplete_or_wrong_architecture_package_metadata_is_rejected(self):
        original = self.command
        for output in ('adb\t1\t\t1\tarm64\t\n',
                       'adb\t1\tandroid-platform-tools\t1\tamd64\t\n'):
            def command(args, **kwargs):
                if args[:2] == ['dpkg-query', '--show']:
                    return subprocess.CompletedProcess(args, 0, output, '')
                return original(args, **kwargs)

            with self.subTest(output=output):
                with mock.patch.object(collector.subprocess, 'run', side_effect=command):
                    with self.assertRaises(collector.CollectionError):
                        self.collect()
                self.assert_clean()

    def test_ambiguous_package_ownership_is_rejected(self):
        original = self.command

        def command(args, **kwargs):
            if args[:2] == ['dpkg-query', '--search']:
                output = f'adb, other: {args[2]}\n'
                return subprocess.CompletedProcess(args, 0, output, '')
            return original(args, **kwargs)

        with mock.patch.object(collector.subprocess, 'run', side_effect=command):
            with self.assertRaisesRegex(collector.CollectionError, 'ownership'):
                self.collect()
        self.assert_clean()

    def test_glibc_named_library_cannot_hide_a_non_glibc_package(self):
        self.packages[self.files['libc.so.6']] = ('fake-libc:arm64', 'not-glibc')
        with self.assertRaisesRegex(collector.CollectionError, 'non-glibc owner'):
            self.collect()
        self.assert_clean()

    def test_conflicting_soname_destinations_are_rejected(self):
        other = self.system / 'other-libadb.so.0'
        other.write_bytes(elf_bytes())
        self.packages[other] = self.packages[self.files['libadb.so.0']]
        self.ldd_overrides['libadb.so.0'] = f'libadb.so.0 => {other} (0x1234)\n'
        with self.assertRaisesRegex(collector.CollectionError, 'Conflicting library'):
            self.collect()
        self.assert_clean()

    def test_dependency_limit_is_bounded(self):
        with mock.patch.object(collector, 'MAX_LIBRARIES', 3):
            with self.assertRaisesRegex(collector.CollectionError, 'limit exceeded'):
                self.collect()
        self.assert_clean()

    def test_loader_environment_is_sanitized(self):
        with mock.patch.dict(os.environ, {'LD_LIBRARY_PATH': '/untrusted', 'LD_PRELOAD': 'bad.so'}):
            original = self.command

            def verify(args, **kwargs):
                self.assertFalse(any(key.startswith('LD_') for key in kwargs['env']))
                return original(args, **kwargs)

            with mock.patch.object(collector.subprocess, 'run', side_effect=verify):
                self.collect()

    def test_publication_race_does_not_overwrite_new_user_directory(self):
        original = collector.make_notice

        def create_competing_output(*args):
            self.output.mkdir()
            (self.output / 'user-data').write_text('preserve')
            return original(*args)

        with mock.patch.object(collector, 'make_notice', side_effect=create_competing_output):
            with self.assertRaises(FileExistsError):
                self.collect()
        self.assertEqual((self.output / 'user-data').read_text(), 'preserve')
        self.assertEqual(list(self.root.glob('.collect-linux-adb-*')), [])

    def test_failed_publication_removes_only_own_empty_reservation(self):
        with mock.patch.object(Path, 'rename', side_effect=OSError('rename failed')):
            with self.assertRaisesRegex(OSError, 'rename failed'):
                self.collect()
        self.assert_clean()

    def test_copy_failure_cleans_partial_stage(self):
        with mock.patch.object(collector.shutil, 'copyfile', side_effect=OSError('copy failed')):
            with self.assertRaisesRegex(OSError, 'copy failed'):
                self.collect()
        self.assert_clean()

    def test_timeout_is_reported_and_no_output_is_left(self):
        with mock.patch.object(collector.subprocess, 'run', side_effect=subprocess.TimeoutExpired('dpkg-query', 60)):
            with self.assertRaises(collector.CollectionError):
                self.collect()
        self.assert_clean()

    def test_successful_ldd_with_stderr_diagnostic_is_rejected(self):
        original = self.command

        def command(args, **kwargs):
            result = original(args, **kwargs)
            if args[0] == 'ldd':
                result.stderr = 'warning: unknown loader state'
            return result

        with mock.patch.object(collector.subprocess, 'run', side_effect=command):
            with self.assertRaisesRegex(collector.CollectionError, 'ldd diagnostic'):
                self.collect()
        self.assert_clean()

    def test_successful_main_prints_new_source_directory(self):
        with mock.patch('sys.stdout') as stdout:
            result = collector.main(['--adb', str(self.adb), '--output-dir', str(self.output)])
        self.assertEqual(result, 0)
        self.assertIn(str(self.output), ''.join(call.args[0] for call in stdout.write.call_args_list))

    def test_main_reports_failure_without_traceback(self):
        self.fail_patch = True
        with mock.patch('sys.stderr') as stderr:
            self.assertEqual(collector.main(['--adb', str(self.adb), '--output-dir', str(self.output)]), 1)
        self.assertIn('collect-linux-adb:', ''.join(call.args[0] for call in stderr.write.call_args_list))
        self.assert_clean()


class HostTests(unittest.TestCase):
    def test_only_ubuntu_2404_arm64_is_accepted(self):
        for system, machine, release, valid in [
            ('linux', 'aarch64', {'ID': 'ubuntu', 'VERSION_ID': '24.04'}, True),
            ('linux', 'x86_64', {'ID': 'ubuntu', 'VERSION_ID': '24.04'}, False),
            ('darwin', 'arm64', {}, False),
            ('linux', 'aarch64', {'ID': 'debian', 'VERSION_ID': '12'}, False),
            ('linux', 'aarch64', {'ID': 'ubuntu', 'VERSION_ID': '22.04'}, False),
        ]:
            with self.subTest(system=system, machine=machine, release=release):
                with mock.patch.object(collector.sys, 'platform', system), \
                     mock.patch.object(collector.platform, 'machine', return_value=machine), \
                     mock.patch.object(collector.platform, 'freedesktop_os_release', return_value=release):
                    if valid:
                        collector.check_host()
                    else:
                        with self.assertRaises(collector.CollectionError):
                            collector.check_host()


if __name__ == '__main__':
    unittest.main()
