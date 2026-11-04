# Native script sandbox contracts

Date: 2026-10-31. Scope: `ai-platform-script-runtime` and its isolated native
qualification tools. This is not installed-app, signing, Windows, or macOS release acceptance.

## Public surface and authority

- `SandboxedScriptRuntime.run(request)` and the existing `execute(request)` use
  the same implementation. `ScriptRunRequest`, input/output validation, manifest
  hashes, permission intersection, and audit fields remain intact.
- The only additive toolchain field is
  `SandboxToolchain.linuxLauncher`, defaulting to
  `/usr/libexec/aps/aps-script-sandbox`. It is host configuration, never package
  content, model output, an automatically discovered home executable, or a shell command.
- Main has integrated trusted distribution staging and locator wiring, described
  below. This work lane does not edit packaging, add library dependencies,
  install a helper into the host, or run Gradle.
- Tool presence is not availability. Discovery runs a synthetic boundary probe
  through the actual backend before returning `Available`. Even interpreter
  version discovery is isolated. There is no bare-interpreter fallback.
- macOS returns `E_MACOS_SANDBOX_UNQUALIFIED`; Windows returns
  `E_WINDOWS_SANDBOX_UNQUALIFIED` before profile/work-directory creation or
  interpreter launch. Unknown systems retain `E_PLATFORM_SANDBOX_UNAVAILABLE`.

### Packaging Handoff To Main

Native source (no external library dependencies):

`desktop-viewer/ai-platform/ai-platform-script-runtime/src/main/native/linux/aps-script-sandbox.c`

The local qualification builds used the native system toolchain with these exact flags:

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror -fstack-protector-strong \
  -D_FORTIFY_SOURCE=2 aps-script-sandbox.c -o aps-script-sandbox
```

Main's integrated, fixed distribution resource layout for the current target:

```text
resources/script-sandbox/aps-script-sandbox
resources/script-sandbox/aps-script-sandbox.sha256
```

Main owns `desktop-app/build.gradle.kts`, appRuntime and the integrated
`AiPlatformApp` / `ScriptSandboxLauncherLocator` wiring. Its distribution build
uses C11, O2, Werror, stack protection, PIE and RELRO; FORTIFY alignment with the
local qualification flags is main's build responsibility. Cross-architecture
staging requires `-PapsScriptSandboxLauncherBinary`. Staging validates ELF64,
endianness and `e_machine`, and generates `.sha256` from the trusted staged
distribution build, not from Script-supplied metadata.

The locator uses the fixed `DISTRIBUTION` resource path, rejects symlinks and
unbounded files, verifies SHA-256 and the matching host ELF, and passes the
canonical trusted path through `SandboxToolchain(linuxLauncher = trustedPath)`.
It does not search Skill paths, PATH or home. Invalid packaged helpers produce
a null script runner without a system fallback; other AI remains usable.
The `/usr/libexec/aps` default applies only to the unpackaged host toolchain,
not to a broken packaged-resource lookup. This records main's actual integration
handoff, not installed-package qualification performed by this work lane.

CI coordination: Windows expectations must use
`SandboxAvailability.Unavailable("E_WINDOWS_SANDBOX_UNQUALIFIED")`, never
`Available` or the older generic unavailable code. Linux CI needs the staged
helper wired into the same toolchain used by production; a standalone runtime's
absent helper is `E_LINUX_SANDBOX_HELPER_UNAVAILABLE`, whereas the packaged app
rejects an invalid helper before constructing its runner. Native acceptance that requires both
Python and Node must actually qualify both, not silently skip an unavailable
runtime. No `AiReleaseQualificationTest` or packaging files are edited here.

Frozen native-source SHA-256:
`f3e7ce0d4ef662dd3eaa033988f502a182f9d0d0d8b1a939d71c64ab18479ee2`.
The tested Ubuntu 24.04 arm64 executable SHA-256 is
`582d4b64970fbd2e56dfb4faad2471eb7e2d3dd6a0bdc8588a425c6a411a2795`.
These identify this qualification build, not every compiler's reproducible output.
Main's staged `.sha256` must describe its own distribution build artifacts.

## Linux implementation

The backend is `linux-bubblewrap-prlimit-seccomp-v1`:

1. `prlimit` sets hard and soft CPU, virtual address-space, file-size, open-file,
   and zero-core limits before namespace setup.
2. Bubblewrap creates user, PID, network, IPC, UTS and cgroup namespaces, clears
   environment, drops all capabilities, creates a new session, and enables
   parent-death termination. Namespace/setup failures are errors, not fallbacks.
3. Only `/usr/bin`, `/usr/lib`, `/usr/lib64`, `/lib`, and `/lib64` are optional
   read-only system directory mounts. Node additionally receives only the five
   OS builtin files listed below; Python receives none of those file mounts.
   `/usr`, `/usr/share`, `/usr/share/nodejs`, `/usr/local`, host root/home, provider state,
   credentials, sockets and arbitrary package directories are not mounted.
   These system trees are trusted OS installation inputs; putting secrets in
   them is outside this profile's supported deployment contract.
4. Only the verified script and trusted launcher are bound under `/input`.
   Root and inputs are read-only. There is no procfs mount. `/dev` is
   bubblewrap's minimal private device filesystem, not a host device-tree bind.
   It is explicitly remounted read-only; remounting `/` does not recursively
   protect submounts. In particular, its `/dev/shm` cannot become an unbounded
   additional writable scratch filesystem.
5. `/work` and `/tmp` are separate private, size-limited tmpfs mounts. Output
   publication is through validated stdout, not traversal of writable scratch.
6. The native launcher rejects UID 0, requires inherited finite limits, applies
   NPROC after namespace creation (not against the parent GUI JVM's UID threads), sets
   `PR_SET_NO_NEW_PRIVS`, installs an inherited seccomp filter, then `execv`s
   the host-selected interpreter. Linux x86_64 and little-endian aarch64 syscall
   ABIs are the intended targets; unsupported ABIs fail compilation or execution.
7. Seccomp denies process-producing fork/vfork/clone, clone3 (ENOSYS), sockets
   and socketpair, namespace/mount changes, ptrace/process-memory access,
   keyring access, SysV IPC, perf events, pidfd descriptor stealing, BPF,
   io_uring setup, userfaultfd and open-by-handle. The legacy clone exception
   admits only kernel threads sharing one process/address space, with an
   explicit flag mask and no namespace/vfork/signal flags. x32 and foreign
   syscall architectures are killed before syscall-number interpretation.

This is not a general syscall allowlist, container escape proof, or kernel
vulnerability defense. The host kernel, bwrap, native helper and OS runtime
trees remain trusted. No library such as libseccomp/JNA/JNI is added.

### Node OS Builtins

Debian/Ubuntu's interpreter externalizes five builtin JavaScript inputs. The
Node-only fixed file allowlist is:

```text
/usr/share/nodejs/cjs-module-lexer/lexer.js
/usr/share/nodejs/cjs-module-lexer/dist/lexer.js
/usr/share/nodejs/undici/undici-fetch.js
/usr/share/nodejs/acorn/dist/acorn.js
/usr/share/nodejs/acorn-walk/dist/walk.js
```

Native `dpkg-query` inspection identified the owners as `node-cjs-module-lexer`,
`node-undici` and `node-acorn`; all five were UID-0-owned regular files with mode
0644, canonical paths identical to the literals, and no symlinks. The backend
rejects symlink components and existing non-regular sources before adding
read-only file binds. Missing files remain optional for interpreters with
embedded builtins; actual discovery must still succeed before availability.

No containing package directory, package.json, arbitrary package script,
manifest-supplied path, PATH/home lookup or broad `/usr/share` mount is granted.
These are declared trusted OS interpreter inputs, not new app library
dependencies or Skill dependencies. Node's hostile fixture verifies that all
five files are readable but not writable, unrelated package metadata is absent,
host/symlink access is denied, and positive private scratch access still works.
The allowlist regression locks Node-only gating and identical resource limits
to Python. No C helper, app API, toolchain field, packaging or CI change was
needed for this follow-up.

### Budget Semantics

- `maxCpuMillis`: Linux `RLIMIT_CPU`, aggregate across threads of the single
  permitted process. Whole seconds are rounded down, not up. A maximum below
  1000 ms returns `E_CPU_BUDGET_PRECISION` rather than silently exceeding it.
- `maxMemoryBytes`: hard virtual address-space limit (`RLIMIT_AS`), not RSS,
  whole-kernel memory, or an all-inclusive VM/cgroup allocation measurement.
  Runtime startup must fit too. No retry with a larger limit is permitted.
- `maxOutputBytes`: stdout and stderr share one atomic byte allowance. Each
  scratch tmpfs separately has this byte quota, and each regular file has this
  maximum file size. These are not one combined scratch-plus-stream quota or
  an inode-count quota. SysV IPC is denied rather than allowed to bypass scratch
  quotas. Bubblewrap's `/dev/shm` exists but is explicitly read-only, so named
  POSIX shared-memory creation cannot bypass the scratch quotas.
- `RLIMIT_NOFILE=64`, `RLIMIT_NPROC=16`, and `RLIMIT_CORE=0` are hard limits.
  NPROC is a kernel UID-accounted ceiling, not a newly invented per-run process
  counter; process creation is independently denied by seccomp. UID 0 is not
  supported because it can bypass NPROC accounting.
- Wall timeout and coroutine cancellation cover stdin, execution and pipe
  draining. Supervision tracks only descendants of its own process, gives the
  waiting parent a bounded opportunity to reap children, then force-terminates
  remaining tracked processes. No broad process-name or process-group kill is
  used. Bubblewrap/PID-namespace lifetime and fork denial are the native
  boundary; a Java descendant snapshot alone is not a sandbox.
- Python uses `-I -B`. Node uses `--jitless --no-addons` and its existing heap
  flag. Neither may add package dependencies or broaden mounts. A runtime that
  cannot start under the budget remains unavailable.

### Qualification

**Qualified locally: Linux arm64 Python and Node, Ubuntu 24.04 / Python 3.12.3 /
Node 18.19.1 / bubblewrap 0.9.0**, using the actual Kotlin backend argv and native launcher:

- All 10 Python hostile cases passed: host/symlink read-write denial and positive
  scratch/input controls, including `/dev/shm` and `/dev` write denial;
  network socket denial; fork/descendant denial with a
  permitted thread positive control; SysV IPC/keyring denial; address-space
  memory bound; scratch volume quota; file size; CPU termination; shared
  stdout/stderr limit; cancellation. No observed live descendants survived.
- All 9 Node hostile cases passed: host/symlink and immutable input denial,
  read-only OS builtin files without package-directory exposure, network denial
  for IPv4/IPv6/Unix sockets, fork/descendant denial, memory, scratch/file-size
  limits, CPU, aggregate output and cancellation. Its 320 MiB allocation was
  rejected under the unchanged 256 MiB address-space cap. No live descendants survived.
- All 7 native JVM API checks passed for each language (14 total): discovery, `run` structured output and
  audit, CPU, aggregate output, memory, wall timeout, cancellation followed by
  successful reuse and scratch cleanup. All 14 focused JVM tests also passed
  on native Linux, in addition to passing on the macOS host.
- The namespace/resource command is exported by Kotlin, not reconstructed by
  the Python runner. The outer disposable container had no host mounts and no
  attached network during qualification; payload ran as non-root UID 65534.
  CPU and address-space limits were not increased to obtain these passes.
  Hostile commands retained 1 CPU second, 256 MiB address space, 64 KiB output
  and per-scratch-volume limits, 64 descriptors, and the helper's NPROC limit.
  Node retained `--jitless --no-addons --max-old-space-size=64`.

This is not native x86_64, installed-package, hosted CI or release acceptance.
Main has integrated the trusted packaging/path wiring described above; its
installed-package/full-build acceptance is separate from these native probes.
The conventional `/usr/libexec/aps` default remains unavailable where it is not
shipped and is not used as a packaged-resource failure fallback.

The earlier Node exit-134 startup failure was caused by missing externalized
builtin files, not a demonstrated memory-limit failure. The bounded follow-up
resolved it with the five-file allowlist above and reran hostile/API acceptance
for both languages without relaxing budgets or adding broader mounts. Earlier
Python-only qualification and Node-unavailable reports are superseded by this
local arm64 result, not by an assumption about other machines or Node builds.

The original x86_64 OrbStack VM is **unqualified under the declared budgets**:
its emulation layer aborted before the interpreter under a 256 MiB address-space
limit. Even `/usr/bin/true` failed under the model's maximum 1 GiB limit with
`rosetta error: mmap_anonymous_rw mmap failed`. No limit was relaxed and no
unrestricted hostile fixture was launched there.

The frozen helper source compiled on that x86_64 VM with the same warning/error
and hardening flags, but that is compile evidence only. In particular, the x32
ABI-kill branch has not received native x86_64 execution qualification here.

## macOS: Fail Closed

`/usr/bin/sandbox-exec` and a native compiler are present, but are not sufficient
to enable this backend. `scripts/ai-sandbox/probe-macos.py` compiles a tiny
native fixture and records three explicit `(deny default)` profiles: minimal,
with sysctl reads, and with narrowly scoped metadata reads. No profile grants
allow-default, home access, network, fork, or credential paths.

On this Darwin 27.2.0 arm64 host all three executions exited with SIGABRT (-6,
shell convention 134), with no boundary-fixture output. The positive native
unsandboxed `hello` control succeeded. A separate bounded resource probe
reported `setrlimit(RLIMIT_AS, 64 MiB) = -1, errno=22`; a 128 MiB allocation and
touch then succeeded. These observations are specific host evidence, not a
claim that every Darwin sandbox profile or every resource API is impossible.

Consequently there is no enabled macOS backend/helper. A future implementation
must demonstrate positive execution and read-only input/private scratch, denied
host access/network/process creation, inherited confinement, hard supported
resource bounds, output limits and cancellation together. An RSS watcher,
`ProcessBuilder`, `ulimit`, an allow-default profile, or merely finding
`sandbox-exec` is not an acceptable substitute. Apple's installed
`sandbox-exec(1)` manual marks this command deprecated and points to App Sandbox.

## Windows: Fail Closed

There is no Windows kernel, Windows SDK/compiler, Wine, or PowerShell available
for native acceptance on this host. No guessed Windows helper is implemented.
The JVM tests inject Windows OS selection and verify the exact unavailable code
before any work root is created or interpreter is launched.

A future Windows backend must qualify filesystem access/ACLs and network
capabilities using a real AppContainer or an equivalently demonstrated native
boundary. A restricted token, low integrity, working directory, cleared
environment or Job Object alone is insufficient. Process creation must be
suspended until assignment to a non-breakaway Job Object with memory, CPU,
active-process and kill-on-close limits. Handle inheritance, named IPC,
loopback/network denial, native runtime startup, temp/output accounting,
descendant termination and cancellation all need hostile Windows tests.
AppContainer/Job API documentation is a design reference, not execution evidence.

## Reproduction And Evidence

All probes use synthetic sentinel/input files and sanitized child environments.
They never inspect real credentials, enumerate environment values, mount the
macOS host into Linux, or signal unrelated processes. Linux argv is exported by
the actual Kotlin backend, not reimplemented in the Python probe runner.

```sh
/usr/bin/python3 scripts/ai-sandbox/check-jvm.py
/usr/bin/python3 scripts/ai-sandbox/lint-kotlin.py
/usr/bin/python3 scripts/ai-sandbox/probe-macos.py
```

`check-jvm.py` uses existing cached compiler/test jars and existing model/registry
classes, compiles only this module with `-Werror`, runs its focused tests, and
exports `commands.json`. It writes evidence to a new private temporary directory.
It does not rebuild upstream modules or substitute for the main serial Gradle
checks. `lint-kotlin.py` uses the cached ktlint CLI; `--format` is limited to owned
Kotlin sources. No downloads are performed by these two checks.

For an already-running local Docker daemon, `qualify-docker.py <unix-endpoint>
<jvm-evidence-directory>` creates an empty Docker client config and requires a
cached Ubuntu arm64 base or the labelled
`aps-native-sandbox-tools:ubuntu24.04-arm64-v1` tools image; it does not pull
automatically. `--prepare-only` can install stock test OS tools into a disposable
container, cache that OS-only filesystem before copying app sources, inspect
Node package ownership/paths, and remove the container. No host mounts or
credentials are included in the cached tools image.

Subsequent qualification starts a bounded disposable container from that tools
image with `--network none`, runs probes as UID 65534, copies raw logs out, and
removes only its frozen container ID in `finally`. A first run without a tools
cache installs test OS tools and disconnects the setup network before probes.
The outer container permits nested namespaces;
isolation verdicts are based on the inner bwrap/helper policy, not an outer
Docker syscall denial. Image/tool downloads are qualification setup, not new
application dependencies. The main app does not invoke Docker.

For an existing Linux machine, copy only these scripts, native source, and the
exported argv into a private temporary directory. `prepare-linux.py` compiles
the helper and runs a namespace-only positive control. If bwrap is absent on an
Ubuntu machine, it downloads the already-required bwrap package to temporary
storage, checks the package-cache SHA-256, and extracts without installation.
`qualify-linux.py <tools.json> <commands.json>` then checks host read/write and
symlink denial, immutable input, positive scratch controls, sockets, fork and
descendants, SysV IPC/keyrings, memory, CPU, tmpfs/file/output quotas, cancellation
and surviving descendants. It runs the complete Node hostile suite after Python
regression, then the JVM driver runs public runtime API acceptance for both.
The x32 ABI-kill fixture runs only on x86_64.

Raw local evidence captured during this task:

- macOS: `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-seatbelt-ia2cimv7/`
  (`results.json`, the exact `.sb` profiles, stdout/stderr, native fixture).
- Final JVM compile/tests/argv: `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-sandbox-jvm-0cd0n58z/`.
- Final successful native Linux Python and Node run:
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-my0452k9/`.
  `jvm-tests.stdout` contains all 14 focused and 14 API results;
  `linux-1/results.json` contains every hostile case, timing, exit status and
  descendant check across all 19 hostile cases;
  `node-runtime-files.stdout` records package ownership and canonical file metadata;
  `linux-0/aps-script-sandbox` is the tested arm64 executable;
  `operations.json` records cached-image reuse, isolated network setup and container removal.
  `container-boundary.stdout` records `[]` mounts, only the `none` network without
  addresses/gateway, and image ID
  `sha256:350720b95dd4542e30a2c990776682fc913c686741ab4442bb6b706c59f6309c`.
- OS tools cache preparation and first native Node package inspection:
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-av5rz267/`.
- Previous Python-only qualification and Node missing-builtin failure:
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-ms844417/`.
  It remains historical evidence; the bounded follow-up above supersedes the Node gap.
- x86_64 VM preparation: `/tmp/aps-native-linux-jg_t5xcw/` in the existing
  `Yuchuan` VM; failed full-profile run: `/tmp/aps-linux-qualification-4t5wkis5/`.
  Current-source compile-only evidence: `/tmp/aps-native-x86-compile-d1uk3n4_/`.
- First native container run:
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-xeey5t1o/`.
  Its procfs mount failure and child-reaping regression are failures, not passes;
  the container was removed. The implementation was corrected afterward.
- Subsequent pre-fix native runs:
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-n_jwiypy/`
  records the scratch-mount ordering failure;
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-8zdl68hk/`
  records the inherited NPROC/JVM discovery failure before NPROC moved into the
  post-namespace helper. All temporary qualification containers were removed.
- Intermediate Python/API pass before explicit read-only `/dev` hardening:
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-r6vxxzep/`.
  The final run above supersedes it and includes the additional device-tmpfs
  write-denial assertions. Source/test freeze followed that final native pass;
  full serial Gradle verification remains main's responsibility.
- Initial Docker Hub pull failed before container creation (registry EOF):
  `/private/var/folders/zy/n6p6rygx2hzdwhgfqcj5n9jm0000gn/T/aps-native-docker-y22ymopr/`.

## Primary References

- Linux kernel: [Seccomp BPF](https://docs.kernel.org/userspace-api/seccomp_filter.html)
  and [no_new_privs](https://docs.kernel.org/userspace-api/no_new_privs.html).
- Upstream bubblewrap: [source/manual](https://github.com/containers/bubblewrap)
  and its documented statement that callers own the sandbox policy.
- Linux man-pages project: [getrlimit/setrlimit](https://man7.org/linux/man-pages/man2/getrlimit.2.html)
  for AS, CPU, NPROC and file-size semantics, especially UID-0 NPROC exceptions.
- Apple: installed `sandbox-exec(1)` and `setrlimit(2)` manuals;
  [App Sandbox Design Guide](https://developer.apple.com/library/archive/documentation/Security/Conceptual/AppSandboxDesignGuide/AboutAppSandbox/AboutAppSandbox.html);
  [XNU resource-limit implementation](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_resource.c).
- Microsoft: [AppContainer isolation](https://learn.microsoft.com/en-us/windows/win32/secauthz/appcontainer-isolation),
  [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects),
  [extended job limits](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information),
  and [AssignProcessToJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject).
