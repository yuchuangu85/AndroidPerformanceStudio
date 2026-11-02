#!/usr/bin/env python3
"""Collect installed Ubuntu 24.04 arm64 adb into a new, relocatable source tree.

Run on a trusted build host provisioned from official Ubuntu apt repositories.
Only adb, ldd, dpkg-query and patchelf are executed. This is not a downloader or
a repository-signature verifier; the later prepare step owns manifests/hashes.
"""

import argparse
from collections import deque
from dataclasses import dataclass
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile


COMMAND_TIMEOUT = 60
MAX_LIBRARIES = 256
MAX_NOTICE_BYTES = 8 * 1024 * 1024
DOC_ROOT = Path('/usr/share/doc')
COMMON_LICENSE_ROOT = Path('/usr/share/common-licenses')
PACKAGE_FORMAT = (
    '${binary:Package}\t${Version}\t${source:Package}\t${source:Version}'
    '\t${Architecture}\t${Origin}\n'
)
PACKAGE_NAME = re.compile(r'[a-z0-9][a-z0-9+.-]*(?::[a-z0-9-]+)?')
SONAME = r'[A-Za-z0-9_+.-]+'
ADDRESS = r'\(0x[0-9a-fA-F]+\)'


class CollectionError(RuntimeError):
    pass


def run(command):
    # Do not let the caller's loader overrides contaminate the apt dependency set.
    env = {key: value for key, value in os.environ.items() if not key.startswith('LD_')}
    env.update(LC_ALL='C', LANG='C', LANGUAGE='C')
    try:
        result = subprocess.run(
            [str(arg) for arg in command], check=True, capture_output=True,
            text=True, encoding='utf-8', env=env, timeout=COMMAND_TIMEOUT,
        )
    except subprocess.CalledProcessError as error:
        raise CollectionError(
            f'{command[0]} failed: {error.stderr or error.stdout or error}'
        ) from error
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as error:
        raise CollectionError(f'{command[0]} failed: {error}') from error
    if command[0] == 'ldd' and result.stderr.strip():
        raise CollectionError(f'Unexpected ldd diagnostic: {result.stderr.strip()}')
    return result.stdout


def check_host():
    if sys.platform != 'linux' or platform.machine() not in ('aarch64', 'arm64'):
        raise CollectionError('Collection requires a native Linux arm64 host')
    release = platform.freedesktop_os_release()
    if release.get('ID') != 'ubuntu' or release.get('VERSION_ID') != '24.04':
        raise CollectionError('Collection requires Ubuntu 24.04')


def check_elf(path):
    with path.open('rb') as stream:
        header = stream.read(20)
    if (len(header) != 20 or header[:6] != b'\x7fELF\x02\x01'
            or int.from_bytes(header[18:20], 'little') != 183):
        raise CollectionError(f'Not a native little-endian ELF64 AArch64 file: {path}')


def adb_revision(output):
    versions = [line for line in output.splitlines() if line.startswith('Version')]
    match = re.fullmatch(r'Version ([0-9][A-Za-z0-9.+:~_-]*)\s*', versions[0]) if len(versions) == 1 else None
    if match is None or not re.search(r'^Android Debug Bridge version ', output, re.M):
        raise CollectionError('adb version must report one real Version (not just protocol 1.0.41)')
    return match.group(1)


def parse_ldd(output):
    """Accept only resolved dependencies and the kernel-provided virtual DSO."""
    dependencies = []
    for line in output.splitlines():
        line = line.strip()
        if not line:
            continue
        if 'not found' in line:
            raise CollectionError(f'Missing dependency: {line}')
        if re.fullmatch(r'linux-vdso\.so\.1\s+' + ADDRESS, line):
            continue
        match = re.fullmatch(r'(' + SONAME + r')\s+=>\s+(/[^\s()]+)\s+' + ADDRESS, line)
        if match:
            name, value = match.groups()
        else:
            match = re.fullmatch(r'(/[^\s()]+)\s+' + ADDRESS, line)
            if not match:
                raise CollectionError(f'Unknown or non-absolute ldd entry: {line}')
            value = match.group(1)
            name = Path(value).name
        path = Path(value)
        if (not re.fullmatch(SONAME, name) or name in ('.', '..')
                or '..' in path.parts or not path.is_absolute()):
            raise CollectionError(f'Unsafe ldd entry: {line}')
        dependencies.append((name, path))
        if len(dependencies) > MAX_LIBRARIES:
            raise CollectionError('ldd dependency limit exceeded')
    if not dependencies:
        raise CollectionError('ldd reported no resolved dynamic dependencies')
    return dependencies


@dataclass(frozen=True)
class Package:
    name: str
    version: str
    source: str
    source_version: str
    architecture: str
    origin: str


class PackageDatabase:
    def __init__(self):
        self.packages = {}
        self.owners = {}

    def owner(self, path):
        real = path.resolve(strict=True)
        if real in self.owners:
            return self.owners[real]
        candidates = list(dict.fromkeys((real, path)))
        # Noble's merged-/usr spelling can differ from dpkg's recorded path.
        for candidate in list(candidates):
            value = str(candidate)
            if value.startswith(('/usr/lib/', '/usr/bin/', '/usr/sbin/')):
                alias = Path(value[4:])
            elif value.startswith(('/lib/', '/bin/', '/sbin/')):
                alias = Path('/usr' + value)
            else:
                continue
            if alias.resolve() == real and alias not in candidates:
                candidates.append(alias)
        for candidate in candidates:
            try:
                output = run(['dpkg-query', '--search', str(candidate)])
            except CollectionError:
                continue
            owners = set()
            for line in output.splitlines():
                name, separator, filename = line.rpartition(': ')
                if not separator or filename != str(candidate) or not PACKAGE_NAME.fullmatch(name):
                    raise CollectionError(f'Ambiguous/invalid dpkg ownership: {line}')
                owners.add(name)
            if len(owners) != 1:
                raise CollectionError(f'Expected one package owner for {candidate}')
            name = owners.pop()
            if name not in self.packages:
                fields = run(['dpkg-query', '--show', '--showformat=' + PACKAGE_FORMAT, name])
                rows = fields.splitlines()
                values = rows[0].split('\t') if len(rows) == 1 else []
                if len(values) != 6 or not all(values[:5]):
                    raise CollectionError(f'Incomplete dpkg metadata for {name}')
                package = Package(*values)
                if (package.name != name or not PACKAGE_NAME.fullmatch(package.source)
                        or package.architecture != 'arm64'):
                    raise CollectionError(f'Unexpected package identity/architecture for {name}')
                self.packages[name] = package
            self.owners[real] = self.packages[name]
            return self.owners[real]
        raise CollectionError(f'No dpkg package owns {path} (resolved to {real})')


def dependency_closure(adb, database):
    libraries = {}
    pending = deque([adb])
    visited = set()
    while pending:
        binary = pending.popleft().resolve(strict=True)
        if binary in visited:
            continue
        visited.add(binary)
        for name, path in parse_ldd(run(['ldd', str(binary)])):
            real = path.resolve(strict=True)
            package = database.owner(path)
            if package.source == 'glibc':
                continue
            if name.startswith('ld-linux') or name in ('libc.so.6', 'libm.so.6'):
                raise CollectionError(f'System runtime has unexpected non-glibc owner: {path}')
            if name in libraries and libraries[name] != real:
                raise CollectionError(f'Conflicting library destination {name}')
            check_elf(real)
            libraries[name] = real
            if len(libraries) > MAX_LIBRARIES:
                raise CollectionError('Dependency closure limit exceeded')
            if real not in visited:
                pending.append(real)
    return libraries


def read_notice(path):
    try:
        with path.open('rb') as stream:
            data = stream.read(MAX_NOTICE_BYTES + 1)
        if not data.strip() or len(data) > MAX_NOTICE_BYTES:
            raise CollectionError(f'Empty/oversized copyright or license: {path}')
        return data.decode('utf-8')
    except (OSError, UnicodeError) as error:
        raise CollectionError(f'Required copyright/license unavailable: {path}: {error}') from error


def make_notice(artifacts, database):
    sections = [
        'Ubuntu 24.04 (noble) arm64 adb source collection\n'
        'Installed files mapped using dpkg-query on the build host. The build host\n'
        'must be provisioned from official Ubuntu apt repositories; the local dpkg\n'
        'database alone does not authenticate repository origin.\n\n'
        'Shared-library replacement (including LGPL libraries):\n'
        'Non-glibc shared libraries are separate files in lib64. Users may replace\n'
        'them with ABI-compatible modified versions, keeping the same filenames.\n'
        'adb uses $ORIGIN/lib64; those libraries use $ORIGIN. The system glibc and\n'
        'dynamic loader are not redistributed. No apt or Android SDK is required\n'
        'on the end-user machine. Matching source packages and versions are listed\n'
        'below; obtain their corresponding source from the Ubuntu source archive.\n'
        'Distributors must provide corresponding source/other materials as required\n'
        'by the included licenses; this notice is not itself a source-code offer.\n'
    ]
    grouped = {}
    for destination, source in artifacts:
        package = database.owner(source)
        grouped.setdefault(package, []).append((destination, source))
    common = set()
    for package, files in sorted(grouped.items(), key=lambda item: item[0].name):
        copyright_path = DOC_ROOT / package.name.split(':')[0] / 'copyright'
        copyright_text = read_notice(copyright_path)
        sections.append(
            f'Package: {package.name}\nVersion: {package.version}\n'
            f'Architecture: {package.architecture}\nSource: {package.source}\n'
            f'Source-Version: {package.source_version}\n'
            f'Origin: {package.origin or "not recorded in dpkg; Ubuntu 24.04 build host"}\n'
            'Source archive: Ubuntu 24.04 (noble), including updates/security\n'
            + ''.join(f'File: {dest}\nInstalled source: {src}\n' for dest, src in files)
            + f'Copyright file: {copyright_path}\n\n{copyright_text}\n'
        )
        common.update(name.rstrip('.') for name in re.findall(
            r'/usr/share/common-licenses/([A-Za-z0-9.+_-]+)', copyright_text,
        ))
    for name in sorted(common):
        path = COMMON_LICENSE_ROOT / name
        sections.append(f'Common license: {path}\n\n{read_notice(path)}\n')
    return '\n'.join(sections)


def output_path(value):
    path = Path(os.path.abspath(os.path.expanduser(str(value))))
    if os.path.lexists(path):
        raise CollectionError(f'Refusing to overwrite existing output: {path}')
    if not path.parent.is_dir() or path.parent.resolve() != path.parent:
        raise CollectionError('Output parent must already exist and contain no symlink components')
    return path


def collect(adb, output_dir):
    destination = output_path(output_dir)
    check_host()
    adb = Path(adb)
    if not adb.is_absolute():
        raise CollectionError('--adb must be an absolute installed path')
    adb = adb.resolve(strict=True)
    check_elf(adb)
    database = PackageDatabase()
    if database.owner(adb).source == 'glibc':
        raise CollectionError('adb must not be a glibc file')
    revision = adb_revision(run([str(adb), 'version']))
    libraries = dependency_closure(adb, database)
    artifacts = [('adb', adb)] + [('lib64/' + name, path) for name, path in sorted(libraries.items())]
    notice = make_notice(artifacts, database)
    with tempfile.TemporaryDirectory(prefix='.collect-linux-adb-', dir=destination.parent) as temporary:
        stage = Path(temporary) / 'source'
        (stage / 'lib64').mkdir(parents=True)
        for relative, source in artifacts:
            target = stage / relative
            shutil.copyfile(source, target)
            target.chmod(0o755 if relative == 'adb' else 0o644)
            run(['patchelf', '--set-rpath', '$ORIGIN/lib64' if relative == 'adb' else '$ORIGIN', str(target)])
        (stage / 'NOTICE.txt').write_text(notice, encoding='utf-8')
        (stage / 'source.properties').write_text(
            f'Pkg.Desc=Ubuntu arm64 Android Debug Bridge\nPkg.Revision={revision}\n', encoding='utf-8',
        )
        # Reserve exclusively: rename alone could overwrite someone else's empty directory.
        destination.mkdir()
        reservation = destination.stat()
        try:
            stage.rename(destination)
        except BaseException:
            try:
                current = destination.lstat()
                if (current.st_dev, current.st_ino) == (reservation.st_dev, reservation.st_ino):
                    destination.rmdir()
            except FileNotFoundError:
                pass
            raise
    return destination


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        destination = collect(args.adb, args.output_dir)
    except (CollectionError, OSError) as error:
        print(f'collect-linux-adb: {error}', file=sys.stderr)
        return 1
    print(destination)
    return 0


if __name__ == '__main__':
    sys.exit(main())
