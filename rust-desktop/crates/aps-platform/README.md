# aps-platform

Independent Rust desktop host boundary. This crate has no third-party dependencies.

- `ProcessRunner::run` is blocking. Invoke it on a background executor.
- `ProcessRequest` passes explicit argument values to the OS, not shell source.
- stdout and stderr drain concurrently with separate byte limits (maximum 64 MiB
  each). Stdin is closed. Cancellation, timeout and output-limit failures kill and
  reap the direct child.
- Unix children start in an owned process group, signalled on forced termination.
  This is not a security sandbox. Windows descendant job confinement and daemons
  that intentionally escape a process group are not covered. After termination,
  reader completion has a bounded grace period rather than an unbounded join.
- Errors omit program paths, arguments, environment values and captured stderr.
- `AppDirectories` uses a new `AndroidPerformanceStudio-Rust` namespace for data,
  cache and scratch directories; it never reads Compose configuration.
- `atomic_write` uses an exclusive same-directory temporary file, file sync and
  rename. Failed replacement never deletes or truncates the old destination.
  It is not a multi-file transaction or a promise of power-loss durability.
- `TempWorkspace` owns an exclusively created directory and removes only that
  directory on drop. It is not permission isolation for arbitrary untrusted code.

Test sources under `tests/` are deferred until the full rewrite is implemented,
as requested. No test from this platform slice has been executed. A macOS
`cargo check -p aps-platform` completed while the slice was being integrated;
that is compilation evidence only, not process/cancellation/runtime acceptance.
