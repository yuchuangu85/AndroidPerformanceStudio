# pnpm

## 12.3.4

### Patch Changes

- Sped up dependency resolution in large workspaces [#14352](https://github.com/pnpm/pnpm/issues/14352).

- pnpm 12 now accepts the boolean settings as command-line flags on every command that takes them in pnpm 11, for example `pnpm install --unsafe-perm`, `pnpm add foo --offline`, and `pnpm install --dangerously-allow-all-builds`. pnpm 12 rejected them with `unexpected argument`, which failed every install on Vercel, whose build runs `pnpm install --unsafe-perm` [#14346](https://github.com/pnpm/pnpm/issues/14346).

  `pnpm remove` now accepts `--unsafe-perm`, the same flag `pnpm install`, `pnpm add`, and `pnpm update` take.

## 12.3.3

### Patch Changes

- Fixed concurrent installs sharing a store occasionally failing with an ENOENT error while importing a package file [#14353](https://github.com/pnpm/pnpm/issues/14353).

- Sped up writing the lockfile in large workspaces [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Sped up dependency resolution in large workspaces [#14352](https://github.com/pnpm/pnpm/issues/14352).

- pnpm now runs through Node.js when it was installed by a tool that skips build scripts, such as Vercel's `packageManager` provisioning, Bun, Deno, or `npm install --ignore-scripts`. Those installs previously failed with `syntax error near unexpected token ')'`. They still cannot run pnpm on Windows. On macOS only a shell can start it [#14346](https://github.com/pnpm/pnpm/issues/14346).

## 12.3.2

### Patch Changes

- `pnpm audit --fix update` no longer aborts when a vulnerable package has no safe version inside its declared range [#14508](https://github.com/pnpm/pnpm/issues/14508). The run updates every package it can and lists the rest as remaining.

- `pnpm install` no longer reruns root lifecycle scripts when the global virtual store contains an unfinished-build marker in a package slot that the current lockfile does not use [pnpm/pnpm#14485](https://github.com/pnpm/pnpm/issues/14485).

- Sped up installs that have no lockfile. pnpm now links packages whose dependency subtree has no peer dependencies into the virtual store while resolution is still running.

- `pnpm run` and `pnpm exec` now start without reinstalling on filesystems that keep sub-millisecond mtimes, such as NTFS. Previously, every run on those filesystems reinstalled first [pnpm/pnpm#14486](https://github.com/pnpm/pnpm/issues/14486).

- `pnpm import` now keeps the versions recorded in `package-lock.json`, `npm-shrinkwrap.json`, or `yarn.lock` when it generates `pnpm-lock.yaml`. A range in `package.json`, a catalog, or an override still decides which versions are eligible, and the recorded version is preferred among them. The generated lockfile previously could pin newer versions than the source lockfile [#14476](https://github.com/pnpm/pnpm/issues/14476).

  `pnpm import` in a workspace now imports every workspace project into the shared lockfile. It previously imported only the project in the current directory.

  `pnpm import` now fails with `ERR_PNPM_LOCKFILE_NOT_FOUND` when none of the three source lockfiles is present. It also fails with `ERR_PNPM_YARN_LOCKFILE_PARSE_FAILED` when it cannot parse `yarn.lock`. It previously generated a lockfile from scratch in both cases.

  `pnpm import` always resolves locally. It warns when `--pnpr-server` or the `pnpr-server` setting is given and does not use the server.

- Sped up installs in large workspaces. Discovering the workspace projects no longer enumerates every matched directory to learn which manifest files it holds [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Sped up installs in large workspaces. The resolver and the peer pass allocate less for every dependency edge [#14352](https://github.com/pnpm/pnpm/issues/14352).

- `pnpm self-update`, `pnpm with`, and automatic package-manager version switching no longer wait through registry retry delays when a configured registry has no signatures and `registry.npmjs.org` is unavailable [#14483](https://github.com/pnpm/pnpm/issues/14483).

- Sped up installs in large workspaces. Saving the lockfile is faster, and the install finishes without waiting for memory cleanup [#14352](https://github.com/pnpm/pnpm/issues/14352).

- `pnpm install` now relinks workspace packages when `publishConfig.linkDirectory` changes. Frozen installs report an outdated lockfile until it is regenerated [pnpm/pnpm#14488](https://github.com/pnpm/pnpm/issues/14488).

- The pnpm npm wrapper keeps its placeholder shebang-less so pnpm 11 can install pnpm 12 through the version store. Wrapper installs must allow lifecycle scripts to install the native binary [#14502](https://github.com/pnpm/pnpm/issues/14502).

- Sped up dependency resolution when there is no lockfile, and for the dependencies a lockfile does not cover.

- Sped up installs in large workspaces. Workspace `link:` targets and importer ids are now derived from the paths' suffixes under the workspace root [#14352](https://github.com/pnpm/pnpm/issues/14352).

- `pnpm install` now reports "Already up to date" when local tarball dependencies have not changed [#14495](https://github.com/pnpm/pnpm/issues/14495).

- `pnpm update` now accepts `--ignore-scripts` and skips lifecycle scripts during the update [pnpm/pnpm#14512](https://github.com/pnpm/pnpm/issues/14512).

- Sped up installs that restore a deleted `node_modules` from a warm global virtual store. pnpm no longer re-links packages that are already fully present in the global virtual store [#14510](https://github.com/pnpm/pnpm/issues/14510).

## 12.3.1

### Patch Changes

- Sped up installs in large workspaces: the anchor for re-rendering workspace `link:` targets is now derived once per project instead of once per dependency edge, and project ordering hashes paths by their raw bytes [#14352](https://github.com/pnpm/pnpm/issues/14352).

- After a self-update from pnpm 12.2 to 12.3, global commands such as `node`, `npm`, and `yarn` failed with `unexpected argument '--shim' found`. Global commands now launch normally, and their first launch migrates the global bin directory to native shims. When self-update downgrades to pnpm 12.2 or older, it keeps the newer native shims so those commands continue to work.

- Sped up installs in large workspaces. The check that verifies each project against the lockfile now runs the projects in parallel [#14352](https://github.com/pnpm/pnpm/issues/14352).

## 12.3.0

### Minor Changes

- Every context-aware global command (`node`, `deno`, `bun`, and the shims created with `pnpm shim add`) is now a native executable on every platform, so environment variables whose names are not valid shell identifiers reach these commands. On Windows, `<name>.exe` replaces the `.cmd` and `.ps1` shims for them. Shims written by earlier pnpm 12 releases are migrated on the next global install or self-update.

- `pnpm remove` and `pnpm update` now accept `--trust-lockfile`, `--no-trust-lockfile`, `--trust-policy`, `--trust-policy-exclude` and `--trust-policy-ignore-after`, the same flags `pnpm install` and `pnpm add` take, so the supply-chain settings can be overridden for a single run. `pnpm remove` verifies the lockfile against the active policies the way `pnpm install` does, and `--trust-lockfile` skips that pass for every entry, not only the package being removed.

  `pnpm` now also honors `--config.trust-lockfile=<value>`, and accepts the bare `--trust-lockfile` / `--no-trust-lockfile` spelling on the commands that previously took the setting from the config file alone.

### Patch Changes

- `pnpm add <local directory>`, `pnpm add <local tarball>`, `pnpm add file:<path>` and `pnpm add <tarball URL>` work again. A specifier given without a `<name>@` prefix is no longer read as a registry package name and rejected with `ERR_PNPM_PACKAGE_MANAGER_ADD_RESOLVE_LATEST` [#14437](https://github.com/pnpm/pnpm/issues/14437).

- Fixed `pnpm deploy --legacy` ignoring `allowUnusedPatches` supplied through `--config.allow-unused-patches` or the `PNPM_CONFIG_ALLOW_UNUSED_PATCHES` environment variable [pnpm/pnpm#14450](https://github.com/pnpm/pnpm/issues/14450).

- Fixed `pnpm install --lockfile-only` writing a lockfile that referenced a missing peer-suffixed snapshot when an npm-aliased dependency took part in a cyclic peer dependency graph. The following `pnpm install --frozen-lockfile` failed with `ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY` [#14449](https://github.com/pnpm/pnpm/issues/14449).

- `pnpm config` now accepts `-g`/`--global`, `--location`, and `--json` before its subcommand [pnpm/pnpm#14421](https://github.com/pnpm/pnpm/issues/14421).

- `pnpm dedupe` now converges in one pass when it re-resolves a lockfile created by pnpm 11, so a second run no longer changes the lockfile [#14455](https://github.com/pnpm/pnpm/issues/14455).

- Fixed detached child processes being terminated on Windows when another program launches `pnpm` directly, without a shell, as `nr` from `@antfu/ni` does [#14447](https://github.com/pnpm/pnpm/issues/14447).

- Fixed `pnpm docs <package>@<version>` ignoring the requested version. It now opens the selected version's homepage and reports a missing version instead of opening the package-level homepage [pnpm/pnpm#14428](https://github.com/pnpm/pnpm/issues/14428).

- Sped up installs in large workspaces. `pnpm-lock.yaml` is now read while the workspace projects are being discovered [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Fixed filtered and recursive `pnpm run` and `pnpm exec` hanging when a script reads from the terminal. Interactive prompts work again in a script that pnpm never runs alongside another one, such as a single `--filter`ed project, `--workspace-concurrency=1`, a dependency chain, or a task declaring `concurrency: 1` [#14397](https://github.com/pnpm/pnpm/issues/14397).

- Fixed false unmet peer errors for auto-installed peers in linked workspace packages.

- Fixed npm global installs on Windows so the PowerShell shims invoke `pnpm.exe`.

- Fixed `pnpm with current <command>` when global options precede it, such as `pnpm --workspace-root with current --version` [pnpm/pnpm#14413](https://github.com/pnpm/pnpm/issues/14413).

  A short-option cluster that mixes a global flag with an option owned by the command, such as `pnpm -ro dist pack-app`, is now parsed like the same options written after the command.

  An option written before the command name is now reported as an unknown option unless that command accepts it, instead of being taken for the command to run. `pnpm -P exec echo` and `pnpm -z exec echo` fail the way `pnpm --tag next exec echo` does.

- Apply pure insertions in zero-context patches at the correct line instead of one line early.

- Improved peer dependency resolution performance when many packages reuse the same peer ranges.

- `pnpm outdated` and `pnpm update` now follow local actions and reusable workflows referenced with GitHub's self-repository syntax (`uses: $/.github/actions/setup`) when looking for outdated GitHub Actions, the same way they follow `./` references.

- The `pnpm install --help` descriptions of `--prod` and `--dev` no longer claim that the flags take precedence over `NODE_ENV`. pnpm does not read `NODE_ENV` when selecting which dependency groups to install [#14445](https://github.com/pnpm/pnpm/issues/14445).

- Sped up installs in large workspaces. The check that decides whether the lockfile needs updating no longer compares every project against every lockfile entry [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Sped up dependency resolution in large workspaces that use `link:` dependencies [#14352](https://github.com/pnpm/pnpm/issues/14352).

- On Linux, pnpm now resolves registry hostnames through the system resolver (`getaddrinfo`), as it already does on macOS and Windows and as pnpm 11 did. Previously, an `/etc/resolv.conf` containing an option the bundled pure-Rust resolver did not recognize, such as `options no_tld_query`, made pnpm ignore the configured nameservers and silently query Google's public DNS instead [#14469](https://github.com/pnpm/pnpm/issues/14469).

- Sped up dependency resolution in large workspaces. The resolver builds fewer lookup keys for each dependency [#14352](https://github.com/pnpm/pnpm/issues/14352).

- `catalogMode` and `--save-catalog` no longer move a local path, tarball, or `workspace:<path>` specifier into a catalog. Such a specifier is resolved against the project that declares it, so one catalog entry cannot mean the same directory for every project that references it [#14437](https://github.com/pnpm/pnpm/issues/14437).

- Sped up installs in large workspaces. The workspace dependency graph is now built once per run instead of twice [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Sped up writing `pnpm-lock.yaml` in large workspaces [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Fixed non-frozen installs through a pnpr server failing instead of regenerating a conflicted lockfile.

- `pnpm update --interactive` renders its checklist the way pnpm 11 does. Group headings and column headers are separators the cursor skips instead of checkboxes that select nothing. The columns of one group line up with the next. `a` toggles all and `i` inverts the selection. The confirmed selection is echoed as a list of package names [#14423](https://github.com/pnpm/pnpm/issues/14423).

- Fixed `pnpm config` commands targeting global configuration to skip project package manager version switching, allowing registry authentication to be configured before pnpm downloads a project-pinned version [pnpm/pnpm#14463](https://github.com/pnpm/pnpm/issues/14463).

- Fixed pnpm retaining the surrounding quotes in `.npmrc` values, including auth tokens expanded from environment variables. This restores authentication with registries configured using `:_authToken="${TOKEN}"` [pnpm/pnpm#14427](https://github.com/pnpm/pnpm/issues/14427).

- Fetch and tarball errors no longer print the secrets of the URL they name. Inline `user:pass@` credentials and the query string or fragment of a signed URL are hidden, so a failed install or `pnpm add <url>` cannot leak them into terminal scrollback or CI logs.

- When `dist-tags.latest` names a version whose manifest pnpm cannot read, the error now names that version and the field it could not decode, instead of reporting the tag as empty.

- Retry transient Windows file-lock errors, including sharing violations, while linking dependencies with the default (isolated) `nodeLinker`. This fixes [pnpm/pnpm#14407](https://github.com/pnpm/pnpm/issues/14407).

- `pnpm run`, `pnpm exec`, `pnpm rebuild`, and the script shortcuts such as `pnpm test` now load the pnpmfile, so `updateConfig` hook settings such as `extraEnv` and `extraBinPaths` reach the scripts they spawn [#14433](https://github.com/pnpm/pnpm/issues/14433).

- The `pnpm` executable of the npm package now works when the package was installed without running its install scripts, as under `--ignore-scripts` or the default build-script block of pnpm and Bun [#14346](https://github.com/pnpm/pnpm/issues/14346). In that case it runs through Node.js and, in a terminal, says how to switch to the native binary.

- Sped up installs in large workspaces. The resolver no longer copies the whole lockfile before resolving [#14352](https://github.com/pnpm/pnpm/issues/14352).

- `minimumReleaseAgeStrict` now defaults to `true` when `minimumReleaseAge` is explicitly configured, whether in `pnpm-workspace.yaml`, the global `config.yaml`, a `PNPM_CONFIG_*` variable, or a CLI flag. The built-in 1440-minute default stays non-strict. Previously an explicit cutoff was treated as non-strict, so immature versions were silently added to `minimumReleaseAgeExclude` instead of being gated with a prompt [#14409](https://github.com/pnpm/pnpm/issues/14409).

- Preserve environment variables whose names are not valid shell identifiers when launching Node.js installed by `pnpm runtime set node --global` on Unix [pnpm/pnpm#14417](https://github.com/pnpm/pnpm/issues/14417).

- Fixed `pnpm repo` and `pnpm docs` failing to open the Windows browser from WSL [pnpm/pnpm#14467](https://github.com/pnpm/pnpm/issues/14467).

- `pnpm link`, `pnpm outdated`, and `pnpm import` now apply pnpmfile `updateConfig` hooks before resolving dependencies.

- Fixed standalone installations to preserve the bundled `node-gyp` files used to build native dependencies.

- Fixed resolution against registries whose version manifests carry `_npmUser`, `dist.attestations`, `dist.unpackedSize`, `dist.fileCount`, or `peerDependenciesMeta` in a shape npm does not use. Such a version was skipped as though it had never been published, so `pnpm add` could fail with "no version found for the latest tag" even though the registry served it.

- `pnpm unpublish` now completes the two-factor authentication a registry asks for instead of failing with `ERR_PNPM_UNAUTHORIZED` while logged in. A 401 that is an OTP challenge starts the web-based authentication flow, or prompts for a classic one-time password. The obtained password is reused by every request of the run [#14464](https://github.com/pnpm/pnpm/issues/14464).

- On Windows, pnpm now resolves host names through the system resolver instead of its own DNS client. The built-in client bound a UDP socket for every lookup, which made Windows Defender Firewall ask to allow `pnpm.exe` again after every `pnpm self-update` [#14405](https://github.com/pnpm/pnpm/issues/14405).

## 12.2.1

### Patch Changes

- Restored the `pnpm` executable target without a file extension so pnpm 12.1 and earlier can upgrade to newer pnpm 12 releases on POSIX systems.

## 12.2.0

### Minor Changes

- Catalogs can now resolve workspace dependencies through the `workspace:` protocol.

### Patch Changes

- Fixed `pnpm audit --fix` failing with `ERR_PNPM_INVALID_FIX_OPTION` when used without a value, including when another flag follows it, as in `pnpm audit --fix --json` [#13261](https://github.com/pnpm/pnpm/issues/13261). Fixed `pnpm audit --fix=override` ignoring the `saveExact` and `savePrefix` settings when writing vulnerability overrides [#11523](https://github.com/pnpm/pnpm/issues/11523).

- Authenticate Node.js runtime downloads from `nodeDownloadMirrors` with URL-scoped npm registry credentials, including bearer tokens, basic auth, and `tokenHelper` [pnpm/pnpm#14334](https://github.com/pnpm/pnpm/issues/14334).

- Fixed detached child processes being terminated after successful commands on Windows.

- Sped up installs in large workspaces by resolving each named `workspace:` dependency (`workspace:*`, `workspace:^`, `workspace:1.2.3`) once and reusing it across every project that declares it, instead of re-resolving it per project.

- Fixed `pnpm install --fix-lockfile` to derive its repair and filtered-merge views from one lockfile snapshot.

- Load pnpmfile `updateConfig` hooks before packing so hook-provided catalogs resolve in `pnpm pack`, `pnpm publish`, and `pnpm stage publish` [pnpm/pnpm#14377](https://github.com/pnpm/pnpm/issues/14377).

- `pnpm deploy` no longer requires `injectWorkspacePackages` to be enabled. A linked workspace dependency is rewritten to a `file:` dependency in the dedicated deploy lockfile, and the peer dependencies it declares are bound to the deployed graph's own resolution.

  When a peer resolves to more than one version in that graph the binding is ambiguous, and choosing between the candidates is exactly what injecting the package would have decided, so the deploy still fails — now with `ERR_PNPM_DEPLOY_AMBIGUOUS_PEER`, which names the package, the peer, and the competing versions, instead of refusing every non-injected workspace up front, and suggests pinning the peer to one version with an `overrides` entry as the way to keep deploying without injection [#9386](https://github.com/pnpm/pnpm/issues/9386).

- Fixed global virtual store hashes for dependency cycles. Every package that transitively depends on an allowed build now includes the engine in its store path, independent of traversal order [pnpm/pnpm#14341](https://github.com/pnpm/pnpm/issues/14341).

- Fixed `ERR_PNPM_CMD_SHIM_CHMOD` when several installs run at once against a shared global virtual store. One install could remove a command shim while another was making it executable ([pnpm/pnpm#14353](https://github.com/pnpm/pnpm/issues/14353)).

- Fixed the PowerShell shim generated by `npm install -g pnpm` on Windows so it invokes the native `pnpm.exe` binary [pnpm/pnpm#14362](https://github.com/pnpm/pnpm/issues/14362).

- Fixed context-aware global shims on WSL2 so native Linux installations dispatch through the project runtime.

- `pnpm install` no longer writes global `minimumReleaseAgeExclude` entries to the project's `pnpm-workspace.yaml` [pnpm/pnpm#14347](https://github.com/pnpm/pnpm/issues/14347).

- Fixed `catalog:` ranges in workspace package peer dependencies being reported as unmet [pnpm/pnpm#14361](https://github.com/pnpm/pnpm/issues/14361).

- `globalDir` and `globalBinDir` are honored wherever they are set, so `pnpm add -g` no longer fails with `ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH` after `pnpm config set -g global-bin-dir` [#14336](https://github.com/pnpm/pnpm/issues/14336). The global `config.yaml` is read again, `PNPM_CONFIG_GLOBAL_DIR` / `PNPM_CONFIG_GLOBAL_BIN_DIR` reach the directories derived from them, and a leading `~/` is expanded before that derivation. A project's `pnpm-workspace.yaml` still cannot set either key.

- Fixed the install progress line reporting `added 0` under `nodeLinker: hoisted`, even when packages were linked into `node_modules` [#14348](https://github.com/pnpm/pnpm/issues/14348).

- An auto-installed optional peer is now resolved to a version its declared peer range accepts, even when the workspace root depends on that package at a version outside the range. Previously the root's version was used and then reported as an unmet optional peer [#13867](https://github.com/pnpm/pnpm/issues/13867).

- Fixed `pnpm run "/pattern/"` running matching scripts one at a time in a single project. Matching scripts now run concurrently up to `workspaceConcurrency`, and their output is prefixed so concurrent lines remain distinguishable [pnpm discussion 14357](https://github.com/orgs/pnpm/discussions/14357).

- Fixed a slowdown at the end of a resolving install in a large workspace. The peer-dependency report now inspects only the projects the resolution flagged, rather than every project in the lockfile ([pnpm/pnpm#14359](https://github.com/pnpm/pnpm/issues/14359)).

- Speed up workspace discovery for literal directories and conventional trailing-star patterns.

  Workspace patterns now follow the same dot-directory rule as pnpm 11: a wildcard no longer matches a dot-prefixed directory, so `packages/*` and `**` skip `packages/.cache` and `.git`. A pattern that names a dot-prefixed directory still matches it, as `packages/.cache` and `packages/.*` do.

- `pnpm audit` now ends its output with a trailing newline, including the `--json`, `--fix`, and `--ignore` output.

- Retry transient Windows file-lock errors while replacing hoisted packages during installation.
  This fixes [pnpm/pnpm#14349](https://github.com/pnpm/pnpm/issues/14349).

- Fixed command-line `--side-effects-cache` overrides being ignored when `pnpm-workspace.yaml` uses the object form of `sideEffectsCache` [pnpm/pnpm#14338](https://github.com/pnpm/pnpm/issues/14338).

- Speed up workspace project discovery in large monorepos: workspace patterns are now probed concurrently and the discovered projects' `package.json` files are read in parallel [#14352](https://github.com/pnpm/pnpm/issues/14352).

- Fixed repeated `pnpm dedupe` runs alternating between peer resolutions when a peer is provided through an npm alias.

- Fixed `pnpm repo <package>` and `pnpm docs <package>` resolving bare package names through the `latest` tag, and prevented malformed package ranges from crashing registry selection.

- Fixed non-ASCII characters in configuration values being mangled during environment-variable substitution. Paths such as `storeDir: ./café-store` are now preserved [#14383](https://github.com/pnpm/pnpm/issues/14383).

## 12.1.0

### Minor Changes

- `pnpm login` and `pnpm adduser` now record the granted token in the global `config.yaml`, under the `_auth` setting, with `--scope`'s scope routed to that registry under `registries`. `pnpm logout` removes it from there, and still from an `auth.ini` an earlier version wrote. Tokens already in `auth.ini` keep working.

- A `scope` set in a project's `pnpm-workspace.yaml` is now ignored, with a warning naming where to set it instead. `pnpm login` records the scope as a `@scope:registry` route in the machine-global `auth.ini`, which outranks `~/.npmrc` in every project — so a repository-committed file could redirect a scope such as `@acme` for all of a user's other projects after one routine login. Use `--scope`, the `PNPM_CONFIG_SCOPE` environment variable, or the global config file instead [#13557](https://github.com/pnpm/pnpm/issues/13557).

- Verified remote build artifacts are persisted in the shared store with their signed origin metadata. Later installs reverify the artifact against current trust, policy, platform, and source before reuse, while invalid remote variants are quarantined per channel ([pnpm/pnpm#13771](https://github.com/pnpm/pnpm/issues/13771)).

- Persist completed recursive tasks so `--resume-from` skips exactly the work that passed during a matching interrupted or failed `pnpm -r run` / `pnpm -r exec` invocation. When no compatible state exists, pnpm retains its graph-based resume behavior.

- Workspace install, rebuild, pack, publish, stage, and lifecycle work now starts as soon as its dependencies finish instead of waiting for an unrelated topological group.

- Added per-task concurrency limits to workspace task orchestration. Set `tasks.<name>.concurrency` in `pnpm-workspace.yaml` to limit how many instances of that task may run across workspace projects at once:

  ```yaml
  tasks:
    build:
      concurrency: 2
  ```

- `sideEffectsCache` now declares the whole of how a package's build output is reused — whether one is restored, whether one is saved, and the remote tier that shares it between machines:

  ```yaml
  sideEffectsCache:
    read: true
    write: true
    remote:
      org: acme
      packages: ['native-addon']
  ```

  `sideEffectsCache: true`, `sideEffectsCacheReadonly`, `remoteSideEffectsCache`, and its `organization` field all keep working. Where a field is set under both spellings the one above wins; where it is set under only one, it is kept.

  Two behaviors change, both bringing this CLI in line with what the Rust one already did: `sideEffectsCacheReadonly: true` now blocks writing to the cache, and setting it alongside `sideEffectsCache: false` gives a read-only view rather than switching the cache off entirely. A cache can also be declared write-only now, to populate one the run does not read.

- Workspace task orchestration ([pnpm/rfcs#23](https://github.com/pnpm/rfcs/pull/23)). `pnpm -r run` and `pnpm -r exec` now schedule per task instead of in topological chunks: a task starts as soon as the tasks it depends on have finished, so a project no longer waits for unrelated projects that happen to share its chunk.

  A new `tasks` section in `pnpm-workspace.yaml` declares what a task depends on, using the `^` convention:

  ```yaml
  tasks:
    build:
      dependsOn: ['^build']
    test:
      dependsOn: ['build']
    lint: {}
  ```

  `^name` means the named task in each of the project's workspace dependencies; a bare `name` means the task in the same project; an entry with no `dependsOn` declares an empty dependency list. A task with no entry behaves as `dependsOn: ['^<its own name>']`, which is exactly what the previous chunked ordering implied — an unconfigured workspace gets the scheduler improvement and nothing else changes meaning. A project without the script is reported skipped and passes its edges through to its own dependencies, so a scriptless package does not sever a chain.

  Also part of this change:

  - A dependency cycle among the tasks of a run is now an error naming the participating tasks (`ERR_PNPM_TASK_CYCLE`) instead of silently running in an arbitrary order. Setting `ignoreWorkspaceCycles: true` downgrades the error to a warning: the cycle's tasks run in an arbitrary order relative to each other.
  - `--resume-from` now skips exactly the transitive dependencies of the anchor package; work unrelated to the anchor still runs.
  - Under `--no-bail`, tasks whose dependencies failed are reported as skipped, not failed, and do not add to the exit code.
  - With `--bail` (the default), the first failure still ends the run at once and nothing new is dispatched — including scripts already queued behind the concurrency limit.
  - `pnpm -r run --dry-run <script>` prints the task graph that would execute without running anything (including skipping the `verifyDepsBeforeRun` check); `--json` emits the tasks and their resolved dependency edges.
  - Output is inherited rather than piped only when at most one script can ever be in flight (`--workspace-concurrency=1`, or the graph forces the scripts to run one after another).

- Added macOS and Windows x64 and arm64 support to remote shared build artifacts [pnpm/pnpm#13771](https://github.com/pnpm/pnpm/issues/13771).

- Generalized the experimental shared-artifact protocol so candidates and signed payloads identify a discriminated subject. Dependency side effects use package and source-integrity subjects, while workspace tasks use project and task subjects.

  This changes shared-artifact request bodies and signed payloads. A pnpr server and its clients have to be on matching versions.

### Patch Changes

- An `_auth` entry in the global config file no longer decides which registry packages come from when something else says. A `registry` or `registries` declared in `pnpm-workspace.yaml` or the global config now wins over the route inferred from a stored credential, which still applies where nothing else declares one. The `pnpm_config__auth` environment variable is unchanged: it stays the way to point a CI runner at a mandated proxy, and still overrides what a repository declares.

- Fixed `pnpm deploy --legacy` to exclude dependencies that are only reachable from unselected workspace projects after `pnpm fetch`.

- Fixed dependency-verification install logs corrupting `pnpm exec` output and ignoring `--silent` [pnpm/pnpm#14197](https://github.com/pnpm/pnpm/issues/14197).

- `pnpm clean` / `pnpm purge` run from a workspace subdirectory now remove each project's own `node_modules` instead of emptying the workspace root's for every project [#14239](https://github.com/pnpm/pnpm/issues/14239). A custom `modulesDir` is resolved against each project directory too.

- `pnpm dlx <pkg>@catalog:` now resolves the specifier through the calling workspace's catalogs instead of failing with `ERR_PNPM_CATALOG_ENTRY_NOT_FOUND_FOR_SPEC` [#14294](https://github.com/pnpm/pnpm/issues/14294).

- Fixed `pnpm doctor` reporting a version that does not match `pnpm --version` [pnpm/pnpm#14225](https://github.com/pnpm/pnpm/issues/14225).

- Pacquet now strips exactly one leading path component from `./`-prefixed tarball entries, matching pnpm and npm's tar extraction semantics and keeping shared store keys consistent.

- Installs whose lockfile carries platform or engine constraints are up to ~150 ms faster when resolution runs: the `node --version` probe behind the installability checks now starts before the lockfile is parsed and finishes while dependencies resolve, instead of running afterwards.

- Treat empty scripts selected by a regular expression as missing before running dependent tasks.

- Filter hidden scripts matched by a regular expression during recursive runs when a visible script also matches.

- Fixed `.mjs` pnpmfile hooks failing to load on Windows, including hooks supplied by config dependencies [pnpm/pnpm#14301](https://github.com/pnpm/pnpm/issues/14301).

- Fixed automatically switched pnpm versions forcing all descendant pnpm processes to use the same version [pnpm/pnpm#14309](https://github.com/pnpm/pnpm/issues/14309).

- Fixed `pnpm deploy --prod` failing when an excluded dev dependency was also declared as an optional peer dependency [pnpm/pnpm#14302](https://github.com/pnpm/pnpm/issues/14302).

- Fixed `pnpm pack` to respect the `files` field when deciding whether to include root-level changelog, history, and notice files.

- `pnpm update -g` no longer downgrades a global package. `--latest` resolves the `latest` dist-tag, which can point at an older release than the one installed — after `pnpm add -g <pkg>@next`, for instance [#14270](https://github.com/pnpm/pnpm/issues/14270).

  `pnpm update -g` also no longer changes the pnpm version. pnpm's own global install belongs to `pnpm self-update` [#14270](https://github.com/pnpm/pnpm/issues/14270).

- When multiple versions of the same package expose the same binary, pnpm now links the binary from the highest version [#14249](https://github.com/pnpm/pnpm/issues/14249).

- `pnpm update` no longer replaces the specifier a project declares for a dependency that is also listed in `overrides`. A `catalog:` reference stays a `catalog:` reference, and a declared range stays as written, instead of being rewritten to the version the override resolved to [#12115](https://github.com/pnpm/pnpm/issues/12115).

- `pnpm update` no longer moves the range a project declares for a dependency that `overrides` also lists, even when the override repeats that range verbatim. Previously the updated `package.json` disagreed with the lockfile, so the next `pnpm install --frozen-lockfile` failed with a specifier mismatch [#14224](https://github.com/pnpm/pnpm/issues/14224).

- Allowed pnpm's shared-artifact client to connect to an artifact-only pnpr tier.

- Rebuilding `node_modules` from an up-to-date lockfile is up to ~200 ms faster: the `node --version` probe that installability checks and store keying need now runs concurrently with the store's warm-cache reads instead of before them.

- Remove the duplicate colon from the one-time password prompt.

- Print errors as JSON on stdout when `--json` is passed to `pnpm view` or its aliases (`info`, `show`, and `v`).

- Installs complete faster on workspaces with many projects: each project's `node_modules` is now linked concurrently.

- Fixed `patchedDependencies` matching for git-hosted dependencies during fresh and frozen installs [pnpm/pnpm#14273](https://github.com/pnpm/pnpm/issues/14273).

- `pnpm pm <command>` works again: the `pm` prefix, which forces pnpm's built-in command over a `package.json` script of the same name, is recognized instead of failing with `ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL` / `Command "pm" not found`. `pnpm pm clean` and `pnpm pm purge` now remove `node_modules` even when the project (or the workspace root) declares a `clean` / `purge` script [#14226](https://github.com/pnpm/pnpm/issues/14226).

- The settings that pnpm accepts as command-line flags are recognized again: `--package-import-method`, `--hoist-pattern`, `--public-hoist-pattern`, `--no-hoist`, `--global-dir`, `--virtual-store-dir`, `--modules-dir`, `--child-concurrency`, `--no-lockfile`, `--strict-peer-dependencies`, `--side-effects-cache`, `--side-effects-cache-readonly`, `--trust-policy`, `--trust-policy-exclude`, `--trust-policy-ignore-after`, and `--optimistic-repeat-install`. Each is accepted anywhere on the command line, spelled either `--setting=value` or `--setting value`, and overrides the same setting read from `pnpm-workspace.yaml` or `.npmrc` [#14281](https://github.com/pnpm/pnpm/issues/14281).

- `pnpm add`, `pnpm update`, and `pnpm remove` now save `package.json` before failing with `ERR_PNPM_IGNORED_BUILDS`. The dependency they were asked to change is already materialized by that point, so the manifest has to record it — otherwise the next install removes the packages again.

- The progress output no longer overwrites the lines above it once it grows taller than the terminal window [#14270](https://github.com/pnpm/pnpm/issues/14270).

- Restoring a dependency's build from the remote side-effects cache no longer downloads files the store already holds.

- Recognize `pnpm install --fix-lockfile`, including filtered installs, and regenerate broken lockfile metadata while preserving compatible locked versions [pnpm/pnpm#14250](https://github.com/pnpm/pnpm/issues/14250).

- Fixed intermittent `Access is denied` failures when concurrent global commands hand off the global bin lock on Windows.

- Fixed the `--shamefully-hoist` CLI option being rejected [pnpm/pnpm#14235](https://github.com/pnpm/pnpm/issues/14235).

- The environment variables for the remote side-effects cache are named for the setting they configure: `PNPM_SIDE_EFFECTS_CACHE_REMOTE_KEY_ID`, `..._BUILDER_ID`, `..._IMAGE_DIGEST`, `..._ARCHITECTURE_BASELINE`, `..._PRIVATE_KEY`, `..._BUILD_ENV`, `..._TRUSTED_KEYS` and `..._PUBLISH`. The `PNPM_REMOTE_SIDE_EFFECTS_CACHE_*` names keep working, and the new one wins when both are set.

- Installs that run no build scripts finish faster, especially in workspaces with many projects.

- A `devEngines.packageManager` range pin on pnpm is now recorded in `pnpm-lock.yaml`'s `packageManagerDependencies` when the running pnpm already satisfies it, using the running version and keeping the range as the recorded specifier. Previously only an exact pin — or a range resolved on the way through a version switch — reached the lockfile, so a range pin written by hand (or by any tool other than `pnpm add` / `pnpm self-update`) left the project without the shared resolution the pin exists to provide.

- Workspace installs are substantially faster (~0.7 s on a 60-project workspace): after hoisting, pnpm now shims only the bins of publicly hoisted workspace packages instead of re-walking every project's `node_modules` to rediscover bins that were already linked.

- Fixed a large install-time regression on macOS for installs that rebuild `node_modules` from a warm store [#14231](https://github.com/pnpm/pnpm/issues/14231). APFS serializes file-cloning and hard-linking syscalls volume-wide, so importing packages one file at a time from many threads was bounded by a per-volume ceiling and got slower the more CPU cores the machine had. On macOS, `pnpm install` now materializes each package once into the store's `links` directory (the same canonical slots `enableGlobalVirtualStore` uses) and copies it into `node_modules/.pnpm` with a single copy-on-write directory clone per package, replacing tens of thousands of per-file syscalls with one per package. Applies with the default `nodeLinker: isolated` when `enableGlobalVirtualStore` is off and `packageImportMethod` is `auto`, `clone`, or `clone-or-copy`; hoisted, global-virtual-store, and explicit `hardlink`/`copy` installs are unchanged.

- Stop in-flight recursive `run` and `exec` commands when bailing after the first failure.

- Warm installs that rebuild `node_modules` on macOS are about 10% faster: creating each package's virtual-store directory now issues fewer filesystem calls.

- An `_auth` credential in an `.npmrc` now authenticates even when its base64 is written without the trailing `=` padding (or with extra padding, or with whitespace inside it), instead of failing with a 401. An `_auth` that is not valid base64, or that carries no `:` between the username and the password, now fails with `ERR_PNPM_AUTH_INVALID_BASE64` / `ERR_PNPM_AUTH_MISSING_SEPARATOR` [#14257](https://github.com/pnpm/pnpm/issues/14257).

- Colored output is no longer printed as raw escape sequences in the Windows Command Prompt [#14292](https://github.com/pnpm/pnpm/issues/14292). Commands such as `pnpm list` now style their output there.

## 12.0.0

### Major Changes

- Git dependencies on known hosts (GitHub, GitLab, Bitbucket) are now treated as identities rather than transport choices. Every representation of the same repository — `github:owner/repo`, `owner/repo`, `git+https://…`, `git+ssh://git@…` — resolves through the host's canonical HTTPS URL, and the lockfile never records an SSH URL for them. Repositories whose archive endpoint is anonymously reachable resolve to the host's archive (fast tarball download); all others resolve to a `git` clone of the canonical HTTPS URL, which every machine with access to the repository can fetch.

  To reach a private hosted repository over SSH, configure the machine (not the project) with git's own URL rewriting, for example:

  ```sh
  git config --global url."git@github.com:".insteadOf https://github.com/
  ```

  pnpm shells out to `git`, so the rewrite applies to all of pnpm's git operations automatically. URLs of unknown hosts (self-hosted servers) are unaffected and keep their exact URL, including SSH. URLs with embedded credentials are also kept verbatim and never resolve to a host archive.

  This removes the network probing that previously decided between HTTPS and SSH at resolution time, which could record a transport that only worked on the machine that happened to run the resolution (e.g. an SSH URL that broke CI runners without SSH keys).

- A project's `pnpm-workspace.yaml` may no longer carry a setting pnpm does not recognize. Such a setting used to be ignored in silence — a misspelled `minimumReleaseAge` dropped the policy it was meant to set, and nothing said so. Now it is reported, suggesting the closest real setting name when the key looks like a typo, and it fails the command with `ERR_PNPM_UNRECOGNIZED_WORKSPACE_SETTINGS` when the project pins a pnpm version the running pnpm satisfies: with the pin honored, the setting cannot be meant for a different pnpm version, so it is a mistake to fix rather than a key to ignore. Everywhere else it is a warning, so a project that has yet to be cleaned up keeps working.

  The `pnpm config` subcommands never fail on such a setting, so a broken file can still be inspected and repaired, and `pnpm config get <key>` prints the value with no warnings at all. Keys the global config file cannot set are likewise split between workspace-only settings (still directed to `pnpm-workspace.yaml`) and settings unknown to this version.

- Dependency cycles are now broken canonically during peer resolution: the members of each cycle are ordered by package id, and the edges that close a cycle are always cut at the same place, no matter where the installation walks into the cycle from. Previously the cut depended on the walk path, so installing the same dependencies could produce different lockfiles depending on importer order or resolution order [#13846](https://github.com/pnpm/pnpm/issues/13846), and a peer-resolution verdict computed for one occurrence of a cyclic package could be wrongly reused at another [#13865](https://github.com/pnpm/pnpm/issues/13865).

  With canonical cycle breaking the lockfile is a pure function of the dependency graph: repeated installs, reordered importers, and reordered dependencies all produce byte-identical lockfiles. Peer dependencies of packages inside a cycle keep nearest-wins resolution along the canonical order, and a dependency edge that closes a cycle references an occurrence of its target resolved at the importer level. On large cycle-heavy workspaces peer resolution is 2–3× faster, uses about 25% less memory, and produces a substantially smaller lockfile (fewer redundant peer variants).

  Existing lockfiles keep working: headless (`--frozen-lockfile`) installs consume them unchanged, and installs that skip resolution leave them untouched. The first install that actually re-resolves (for example after a dependency change) re-keys walk-order-dependent peer variants of cyclic packages once.

- `packageImportMethod: auto` now tries hardlinks before cloning on Linux. A reflink materializes a new inode and copies extent bookkeeping inside the filesystem's metadata trees, where a hardlink is one directory entry — on btrfs this roughly halves the time an install spends materializing `node_modules` from a warm store. ext4 installs are unchanged (cloning was never supported there, so `auto` already hardlinked), and macOS keeps clone-first, where APFS `clonefile` is the platform's cheap primitive. Cloning remains the fallback when the store refuses hardlinks, and remains available explicitly via `packageImportMethod: clone`.

- Under `engineStrict`, an install fails when an incompatible package is reached through a regular `dependencies` edge of an installable package, even when that whole subtree hangs off an `optionalDependencies` entry. pnpm v11 installs the package and emits an install-check warning instead. Packages reachable only through optional edges, or through a package that was itself skipped, are still skipped in both versions [#13286](https://github.com/pnpm/pnpm/issues/13286).

### Minor Changes

- Globally installed bins can now follow the project you run them in. The new `globalShims` setting is a record of package names to policies that selects which globally installed packages get project-aware shims; it defaults to `{ node: true, deno: true, bun: true }` and merges key-wise, so `globalShims: { bun: false }` switches one default off and `globalShims: { typescript: true }` adds another package. With the default, a project that pins Node.js through `devEngines.runtime` or `engines.runtime` gets the pinned stable release — authenticated against the Node.js release-team signatures — downloaded on first use and run whenever you type `node` inside the project, with no shell hooks. Candidates that are not signature-verified (Deno, Bun, Node.js prereleases, and ordinary package bins you enable) ask "Do you trust this project?" once per candidate and remember the answer machine-locally; the record values name the policy per package: `"auto"` (or its shorthand `true`) defers to artifact authentication, `"always"` switches without ever asking (useful in CI), and `"prompt"` always asks, even for authenticated candidates. Set `globalShims: false` to disable the feature, or `PNPM_SHIM_BYPASS=1` to bypass it for one invocation. On Windows, programs can keep spawning the global `node.exe` directly, without a shell.

- pnpm installs the other package managers now, not just itself: npm, Yarn Classic, Yarn Berry, Yarn 6 (`yarnpkg/zpm`), and Bun. Each is resolved and fetched through the trusted package-manager registries, and an npm-published one is verified against npm's signature for its exact version before it is executed.

  Three things use it:

  - A git-hosted dependency is prepared with the package manager it asks for. Its `packageManager` / `devEngines.packageManager` pin is honored, and a `yarn.lock` written by Yarn Classic no longer gets installed by Yarn Berry. pnpm provides that package manager when the dependency pinned a version, or when the host cannot satisfy what the dependency needs — so a repository built with Yarn now installs on a machine that has only pnpm, while a host that already has a suitable one keeps using its own.
  - `pnpm dlx` (`pnx`) runs one of them for a single command: `pnx yarn@4 install`, `pnx npm@11 ci`, `pnx bun@1.3.0 install`. Naming a package manager, or a runtime (`node`, `deno`, `bun`), there now provisions the real thing instead of installing the npm package that shares its name — unless the specifier locates a package rather than asking for a released version (`pnx yarn@npm:yarn@1.22.22`, `pnx yarn@yarnpkg/berry`), which installs what it names — `pnx yarn@4` was previously a missing version, since Yarn 4 is published as `@yarnpkg/cli-dist`, and `pnx node@22` now runs that Node.js release rather than a wrapper that downloads one. `--package` naming a package manager picks which of its commands to run, so `pnx --package npm@11 npx create-something` runs that npm's `npx`.
  - `pnpm shim add yarn` links a `yarn` command that runs whatever version the current project pins, and `pnpm shim rm` / `pnpm shim ls` manage those shims. It works for any package, not only package managers. Shims are never created as a side effect of `pnpm setup` or an install — a shim shadows the rest of your `PATH`, so pnpm only writes one when asked.

  Installing a package manager globally (`pnpm add -g yarn`) now makes it follow a project's pin too, the way a globally installed Node.js already follows `devEngines.runtime`: the pinned version runs where a project pins one, and the globally installed copy is the fallback everywhere else. An explicit `globalShims` entry, including `false`, is left as you set it.

  `pnpm add` follows the same rule about what a name means. `pnpm add -g yarn@4` installs Yarn Berry — it used to fail, because npm's `yarn` package stops at Classic — and `pnpm add -g node@22` / `pnpm add -g deno@2` install that Node.js or Deno release rather than a wrapper package that downloads one. In a project, naming a package manager records which one the project uses instead of installing it as a dependency, and naming a runtime records it under `engines.runtime` as `node@runtime:22` already did.

  The declaration goes where the package manager reads it. Yarn is started from a project pin by corepack, which reads only `packageManager` and only accepts an exact version there, so `pnpm add yarn@4` resolves the line and writes `"packageManager": "yarn@4.18.0"` — the same thing `corepack use yarn@4` writes, down to the `+sha512.…` integrity for the Yarn Classic line that corepack pins its tarball with. Every other package manager is recorded in `devEngines.packageManager`, which holds a range. Only one of the two fields is ever left behind: they declare the same thing, and corepack refuses to run a project whose declarations disagree.

  A JavaScript package manager on a machine without Node.js gets a managed LTS runtime to run on.

  What changes for a project coming from v11: `pnpm add yarn` records the project's package manager instead of installing the npm package that shares the name (that package is still reachable as `pnpm add yarn@npm:yarn@1.22.22`), `pnpm add -g yarn` installs the current Yarn line rather than Classic, `pnpm add -g node` / `pnpm add -g deno` and `pnx node` / `pnx deno` install a Node.js or Deno release rather than a wrapper package, and a globally installed package manager defers to a project's pin where there is one.

- Added an opt-in proof of concept that lets installs reuse a dependency's build output across machines, by publishing and restoring signed, organization-scoped artifacts through pnpr instead of running the lifecycle scripts locally.

  Configure it with the new `remoteSideEffectsCache` setting. A workspace names the eligible `organization` and `packages`; everything describing the act of signing — `publish`, `keyId`, `builderId`, `trustedKeys`, `privateKey` and the provenance fields — is refused in `pnpm-workspace.yaml` and read from the global config file or the environment instead.

- Added the `audit.ignorePrune` setting. When set to `true`, `pnpm audit --fix` removes ignored GHSA entries that no longer appear in the audit report.

- `pnpm init` now pins the latest pnpm version, instead of the version of pnpm that ran the command. A project scaffolded by an outdated pnpm therefore no longer inherits that staleness through its own `devEngines.packageManager` / `packageManager` pin [#7490](https://github.com/pnpm/pnpm/issues/7490).

  The version is read from the `latest` tag on the package-manager registries. When that lookup cannot answer — no network, an unreachable or slow registry, `offline`, or a `latest` that the `minimumReleaseAge` / `trustPolicy` settings reject — `pnpm init` pins the running version as before, and never fails or hangs on the lookup. A `latest` that is older than the running pnpm is never pinned either.

- Allowed `pnpm update --patches` to refresh registry revisions through a configured pnpr server while retaining locked package versions.

- Added explicit registry revision selection with `<version>+rN` and `pnpm update --patches` for refreshing revision artifacts without changing package versions. Registry-backed lockfile policy checks recognize historical revisions, and pnpr now preserves safe revision histories from upstream registries.

- Added support for registry replacement tarballs using standard integrity values, explicit revision fields, registry routing from the `registries` setting, non-redirecting integrity-addressed URLs, canonical safe-integer revision numbers, and pnpr proxying for immutable upstream revision artifacts.

- Running `pnpm setup`, `pnpm self-update`, or a command that modifies the global installation (such as `pnpm add --global`) through `sudo` now fails with `ERR_PNPM_SUDO_NOT_SUPPORTED` instead of silently operating on the root user's home directory. pnpm keeps global packages and configuration in the invoking user's home directory, so these commands never need root permissions. Read-only global commands (such as `pnpm bin --global`) still work under sudo.

- `pnpm stage approve` now approves several staged packages at once. Run it without a stage id to pick from the staged versions interactively, or pass a list of stage ids. The whole batch is approved with a single one-time password, and pnpm asks for a new one only once the registry stops accepting it. Inside a workspace, the selected packages are approved in dependency order, and a package whose workspace dependency could not be approved is skipped instead of being published against a dependency that never reached the registry.

### Patch Changes

- Deprecated the pnpmfile `filterLog` hook in pnpm v12. The Rust CLI ignores it and emits a warning.

- The built-in compatibility database no longer adds dependencies that were detected by static analysis of published packages. Those entries named packages that are only imported for their types, so installing them was at best unnecessary and at worst broke the dependent: `@typescript-eslint/types` gained a `typescript` dependency resolved to the newest release, which put TypeScript 7 under older `@typescript-eslint` versions and made ESLint fail with "Cannot read properties of undefined (reading 'Intrinsic')". The database keeps its `@yarnpkg/extensions` entries and pnpm's own curated ones.

- When no directory above the project accepts a hard link — inside an AI agent sandbox that only grants write access to the project, or a container with just the project mounted writable — the default store is now created at `<project>/node_modules/.pnpm-store` instead of in the pnpm home directory. In those environments the home store is either read-only or on another volume, which forces every package to be copied instead of hard linked [#13525](https://github.com/pnpm/pnpm/issues/13525).

## 12.0.0-rc.11

### Minor Changes

- `pnpm stage approve` now approves several staged packages at once. Run it without a stage id to pick from the staged versions interactively, or pass a list of stage ids. The whole batch is approved with a single one-time password, and pnpm asks for a new one only once the registry stops accepting it. Inside a workspace, the selected packages are approved in dependency order, and a package whose workspace dependency could not be approved is skipped instead of being published against a dependency that never reached the registry.

### Patch Changes

- Under `nodeLinker: isolated`, a Bit root-component member whose materialized copy carries no `package.json` now receives sibling symlinks for the dependencies its own lockfile snapshot declares, instead of a symlink to every other member of the root. The all-member fallback remains only when no snapshot exists.

- The update notification now suggests `pnpm self-update` when `PNPM_HOME` manages the pnpm in use, and the [standalone install script](https://pnpm.io/installation) otherwise — under Corepack, or when another package manager installed pnpm. `pnpm self-update` under Corepack names the standalone install script too.

## 12.0.0-rc.10

### Patch Changes

- Fixed pnpm v11 incorrectly reporting `confirmModulesPurge` as unrecognized when set in `pnpm-workspace.yaml`. The Rust CLI now identifies the unsupported option as a pnpm v11 setting instead of suggesting an unrelated setting.

- A `+<algorithm>.<hash>` build in a `devEngines.packageManager` version no longer makes `pnpm install --frozen-lockfile` fail with `ERR_PNPM_FROZEN_LOCKFILE_WITH_OUTDATED_LOCKFILE` on a lockfile a plain install kept rewriting identically [#14124](https://github.com/pnpm/pnpm/issues/14124).

- The built-in compatibility database no longer adds dependencies that were detected by static analysis of published packages. Those entries named packages that are only imported for their types, so installing them was at best unnecessary and at worst broke the dependent: `@typescript-eslint/types` gained a `typescript` dependency resolved to the newest release, which put TypeScript 7 under older `@typescript-eslint` versions and made ESLint fail with "Cannot read properties of undefined (reading 'Intrinsic')". The database keeps its `@yarnpkg/extensions` entries and pnpm's own curated ones.

- `pnpm install --frozen-lockfile` no longer fails with `ERR_PNPM_FROZEN_LOCKFILE_WITH_OUTDATED_LOCKFILE` when the pinned pnpm version recorded in `pnpm-lock.yaml` has to be re-resolved before it can be installed. It runs the pnpm version the lockfile pins and leaves the lockfile unchanged [#14124](https://github.com/pnpm/pnpm/issues/14124).

- Under `nodeLinker: hoisted`, peer-resolution variants of an injected directory dependency (a `file:` snapshot) are materialized as separate copies again instead of collapsing onto the first-seen variant. Each copy keeps its own peer-resolved dependency set, so a project pinning one peer version no longer resolves another project's variant — Bit root components with conflicting peers across injected copies rely on this.

- Fixed `pnpm install --merge-git-branch-lockfiles --frozen-lockfile` failing with `ERR_PNPM_OUTDATED_LOCKFILE` when a branch lockfile predates the removal of a dependency, or its move to another dependency group [#13966](https://github.com/pnpm/pnpm/issues/13966). A dependency that no project declares anymore is no longer reinstated by the merge, and the packages it was the only path to are dropped with it.

- Record the pnpm version a project pins even when the install has nothing else to do. Adding a `devEngines.packageManager` (or `packageManager`) pin to a project whose dependencies are already installed left `packageManagerDependencies` unwritten, so `pnpm install --frozen-lockfile` failed with `ERR_PNPM_FROZEN_LOCKFILE_WITH_OUTDATED_LOCKFILE` while a plain `pnpm install` reported "Already up to date" without recording it [#14124](https://github.com/pnpm/pnpm/issues/14124).

- `pnpm install --frozen-lockfile` no longer fails when `pnpm-lock.yaml` records the pinned pnpm version alongside an engine package the running pnpm does not install it from. An entry pinning another version is still refused, and a plain install rewrites the block [#14124](https://github.com/pnpm/pnpm/issues/14124).

## 12.0.0-rc.9

### Major Changes

- A project's `pnpm-workspace.yaml` may no longer carry a setting pnpm does not recognize. Such a setting used to be ignored in silence — a misspelled `minimumReleaseAge` dropped the policy it was meant to set, and nothing said so. Now it is reported, suggesting the closest real setting name when the key looks like a typo, and it fails the command with `ERR_PNPM_UNRECOGNIZED_WORKSPACE_SETTINGS` when the project pins a pnpm version the running pnpm satisfies: with the pin honored, the setting cannot be meant for a different pnpm version, so it is a mistake to fix rather than a key to ignore. Everywhere else it is a warning, so a project that has yet to be cleaned up keeps working.

  The `pnpm config` subcommands never fail on such a setting, so a broken file can still be inspected and repaired, and `pnpm config get <key>` prints the value with no warnings at all. Keys the global config file cannot set are likewise split between workspace-only settings (still directed to `pnpm-workspace.yaml`) and settings unknown to this version.

### Minor Changes

- Added global build approvals [pnpm/pnpm#14101](https://github.com/pnpm/pnpm/issues/14101).

- Added recursive global outdated checks [pnpm/pnpm#14101](https://github.com/pnpm/pnpm/issues/14101).

- `pnpm config get` and `pnpm config list` now show the settings pnpm acts on under their documented names:

  - `registries` shows the registries pnpm resolves from, merged across every source (`.npmrc`, `pnpm-workspace.yaml`, the global config, CLI flags), in the shape the setting is written in: keyed by registry URL, with the default registry declared as the bare `@` scope. Built-in routes are included — the `@jsr` scope and the `npmjs` and `gh` prefixes — unless pointed elsewhere. Previously `pnpm config get registries` printed `undefined`.
  - `update` and `audit` show the effective sections, whichever spelling set them. The deprecated internal spellings (`updateConfig`, `auditConfig`, `auditLevel`) are no longer listed.
  - `catalogs` shows the complete resolved catalog set — the singular `catalog` block is its `default` entry — whichever spelling declared it.
  - The `registry` and `@scope:registry` entries show the merged routes rather than raw `.npmrc` values, so they always agree with the `registries` view.

- Added support for configuring `stateDir` in the Rust pnpm CLI [pnpm/pnpm#12042](https://github.com/pnpm/pnpm/issues/12042).

- Added bounded workspace concurrency for recursive run and exec commands [pnpm/pnpm#14101](https://github.com/pnpm/pnpm/issues/14101).

- `@pnpm/napi` gained reporter output, reverse dependency queries, and lockfile access.

  `install` and `rebuild` accept `options.reporter` and render pnpm's terminal output — progress line, packages-diff summary, lifecycle output, and the `Done in …` footer. Rendered output goes to stdout, or to an `onOutput` callback for a host that writes its own output through JavaScript. New reporting options: `hideLifecycleOutput`, `ignoredBuildsInstructionText`, and `hideLinkedPkgsDiff`.

  `getDependents` returns the reverse dependency trees behind `pnpm why`, annotated with the `package.json` fields named in `manifestFields`. `renderDependents` returns those trees rendered as tree, parseable, or JSON output.

  `readLockfile` and `writeLockfile` read and write `pnpm-lock.yaml` (or the current lockfile under the virtual store). `filterLockfileByImporters` returns a lockfile narrowed to what the named importers reach. `readModulesManifest` returns the `.modules.yaml` state of an installed `node_modules`.

  Top-level lockfile keys pnpm does not define are no longer dropped when a lockfile is loaded and saved, so state a tool records beside pnpm's own keys survives a rewrite.

- `pnpm` now supports per-branch lockfiles in its Rust engine:

  - `gitBranchLockfile` gives each git branch its own `pnpm-lock.<branch>.yaml`, so two branches can hold different resolutions without conflicting on one file. A branch that has no lockfile yet installs against the shared `pnpm-lock.yaml`.
  - `mergeGitBranchLockfiles` (and the `--merge-git-branch-lockfiles` flag on `pnpm install`) folds every branch lockfile back into `pnpm-lock.yaml` and deletes them, which is what merging a branch into the mainline needs.
  - `mergeGitBranchLockfilesBranchPattern` (and `--merge-git-branch-lockfiles-branch-pattern`) names the branches that merge automatically, so a mainline branch does not have to pass the flag by hand [#12042](https://github.com/pnpm/pnpm/issues/12042).

- Added `PNPM_CONFIG_VIRTUAL_STORE_ONLY` and `PNPM_CONFIG_ENABLE_MODULES_DIR` support to the Rust pnpm CLI.

- Added support for the `lockfileDir` setting and its `--lockfile-dir <dir>` flag on `pnpm install`, `add`, `update`, and `remove`. `pnpm-lock.yaml`, the root `node_modules` holding the virtual store, and the config dependencies now live in the given directory, each project is recorded under its path relative to it, and every project keeps its own `node_modules` of symlinks — so several projects can share one lockfile [#12042](https://github.com/pnpm/pnpm/issues/12042).

- Added support for the `preferSymlinkedExecutables` setting. On POSIX systems, `node_modules/.bin` entries are created as symlinks to the executable files instead of shell shims, and `NODE_PATH` pointing at the virtual store of the workspace root is exported to spawned scripts so they can resolve dependencies from the hoisted store. Like the TypeScript CLI, the setting turns on automatically when `nodeLinker` is set to `hoisted`.

- Added the six CLI flags the TypeScript pnpm CLI accepts but the Rust CLI did not [#14101](https://github.com/pnpm/pnpm/issues/14101):

  - `--stream` prints a recursive command's script output as it arrives, one line at a time, prefixed with the project it came from, instead of letting the scripts write to the terminal directly. `--parallel` implies it, as in pnpm.
  - `--aggregate-output` holds each script's streamed output until the script exits and then prints it as one block, so concurrent projects can't interleave.
  - `--reporter-hide-prefix` drops that project prefix from the scripts' own output lines. On a recursive `pnpm exec`, the opposite spelling `--no-reporter-hide-prefix` turns the prefixing on.
  - `--use-stderr` sends the reporter's output to stderr, leaving stdout for the command's own result.
  - `--ignore-workspace` runs the command as if the project were standalone: no workspace root is discovered, so `pnpm-workspace.yaml` contributes neither settings nor sibling projects, and a blocked dependency build is not scaffolded into its `allowBuilds`.
  - `--workspace-packages` overrides the `packages` patterns of `pnpm-workspace.yaml` for the run.

  The `stream`, `aggregateOutput`, `reporterHidePrefix`, `useStderr`, and `ignoreWorkspace` settings are now read from `pnpm-workspace.yaml`, the global `config.yaml`, and their `PNPM_CONFIG_*` environment variables too.

- Added support for the `shellEmulator` setting. With it enabled, the scripts `pnpm run` executes, a project's own lifecycle scripts, and dependencies' build scripts run in a built-in POSIX shell instead of the platform's (`sh -c`, or `cmd /d /s /c` on Windows), so scripts written for `sh` behave the same on every OS. `scriptShell` is not used while the emulator is on.

- The Rust engine now checks that a package read back from the store is the package it was recorded as. When the tarball's `package.json` names a different name or version than the store entry was keyed for — a broken lockfile, or a registry serving content that doesn't match its metadata — the install fails with `ERR_PNPM_UNEXPECTED_PKG_CONTENT_IN_STORE`. Set the new `strictStorePkgContentCheck` setting to `false` to downgrade the failure to a warning and install from the entry anyway [#12042](https://github.com/pnpm/pnpm/issues/12042).

- `pnpm` now supports three workspace settings in its Rust engine:

  - `includeWorkspaceRoot` (and the universal `--include-workspace-root` / `--no-include-workspace-root` flags) keeps the workspace root project in a recursive `run`, `exec`, `add`, or `test`, which otherwise leave it out.
  - `ignoreWorkspaceCycles` and `disallowWorkspaceCycles` control the report an install makes when workspace projects depend on each other in a cycle: it is a warning by default, an `ERR_PNPM_DISALLOW_WORKSPACE_CYCLES` error under `disallowWorkspaceCycles`, and silent under `ignoreWorkspaceCycles` [#12042](https://github.com/pnpm/pnpm/issues/12042).

- Added support for the remaining pnpm default settings, including recursive command controls, optional dependency selection, workspace-root checks, color modes, lockfile compatibility, and pack manifest options.

- Batch workspace publishing accepts a shared scope-specific credential, rejects mismatched credentials for a registry before publishing, and runs the `publish` and `postpublish` scripts after each completed registry group [pnpm/pnpm#14101](https://github.com/pnpm/pnpm/issues/14101).

- Added the commands the Rust CLI was still missing:

  - `pnpm get <key>` and `pnpm set <key> <value>` — the top-level spellings of `pnpm config get` and `pnpm config set`.
  - `pnpm store status` — reports the packages whose files no longer match the store they were expanded from, failing with `ERR_PNPM_MODIFIED_DEPENDENCY`; and `pnpm store add <pkg>...` — fetches packages into the store without writing a manifest, a lockfile, or `node_modules`.
  - `pnpm env use --global <version>` and `pnpm env list [<selector>]`, the deprecated Node.js-only front end to `pnpm runtime`.
  - `pnpm edit`, `pnpm profile`, `pnpm token`, and `pnpm xmas` now fail with `ERR_PNPM_NOT_IMPLEMENTED` pointing at the npm CLI, instead of being taken for a package script.

- An install that resolves the dependency graph now reports the unmet peer dependencies it leaves behind, matching the TypeScript CLI. By default it warns once — `Issues with peer dependencies found. Run "pnpm peers check" to list them.` — and with `strictPeerDependencies` it fails with `ERR_PNPM_PEER_DEP_ISSUES` after the artifacts are written, listing every unmet peer. This covers `pnpm install`, `add`, `remove`, `update` and `--lockfile-only`; `pnpm dedupe` reported the same verdict already, and now shares the reporting with them. `peerDependencyRules` are applied before the verdict, so a rule that covers every issue leaves nothing to report, and a `--filter`ed install reports only on the projects it installed. An install that skips resolution — a frozen install, or one whose `pnpm-lock.yaml` is already up to date — reports nothing, as in the TypeScript CLI; `pnpm peers check` inspects such a tree [#14098](https://github.com/pnpm/pnpm/issues/14098).

- Added `fetchWarnTimeoutMs` and `fetchMinSpeedKiBps` to the Rust pnpm CLI and its N-API bindings. Slow registry metadata requests and tarball downloads now emit pnpm-compatible warnings without exposing URL credentials, query parameters, fragments, or control characters [pnpm/pnpm#12042](https://github.com/pnpm/pnpm/issues/12042).

- Added filtered and split SBOM generation with per-project lockfiles, including reachable workspace projects and incomplete-graph validation [pnpm/pnpm#14101](https://github.com/pnpm/pnpm/issues/14101).

### Patch Changes

- Kept pending build approvals available after removing an unrelated dependency.

- Fixed resolving the `chcp` command on Windows during `pnpm setup` by looking for `chcp.com` before `chcp` [pnpm/pnpm#13991](https://github.com/pnpm/pnpm/issues/13991).

- A custom fetcher can no longer replace the archive integrity that `pnpm-lock.yaml` pins: the locked value is restored after a `canFetch` or `fetch` hook rewrites the resolution, and delegating a locked archive to a directory or git source now fails instead of installing unverified content.

  The Rust CLI now also loads the pnpmfiles named by the `pnpmfile` setting (a single path or an ordered list), and hands custom fetchers native `localTarball` and `remoteTarball` callbacks — including on a fresh install that has to compute a missing tarball integrity, which is then reused by later offline installs. File maps a fetcher returns are accepted only when they match what those native callbacks extracted.

- `pnpm dedupe` accepts the `pnpm install` options that pnpm documents for it — `--lockfile-only`, `--ignore-scripts`, `--offline`, and `--prefer-offline` — instead of rejecting them with `unexpected argument`. Without `--lockfile-only`, `pnpm dedupe` now also updates `node_modules`, as an install does [#14107](https://github.com/pnpm/pnpm/issues/14107).

- `pnpm dedupe` in the Rust engine now fails with `ERR_PNPM_PEER_DEP_ISSUES` when `strictPeerDependencies` is set and unresolved peer dependency issues remain after deduplication, matching the TypeScript CLI [#14099](https://github.com/pnpm/pnpm/issues/14099). Previously it only ever printed a warning, regardless of the setting.

- `pnpm deploy --prod` and `pnpm deploy --no-optional` no longer list the excluded dependency groups in the deployed `package.json` and `pnpm-lock.yaml`. The deployed lockfile referenced packages that the deploy left out of its graph, so installing in the deploy directory afterwards created dangling symlinks [#13623](https://github.com/pnpm/pnpm/issues/13623).

- `pnpm install --dev` and `pnpm deploy --dev` no longer install optional dependencies, and `--prod` now takes precedence when combined with `--dev`, matching the TypeScript pnpm CLI.

- A dependency published with `"bin": ""`, such as `url-loader@1.1.2`, no longer fails the install with `ERR_PNPM_CMD_SHIM_PROBE_SHIM_SOURCE` [#13962](https://github.com/pnpm/pnpm/issues/13962). An empty `bin` declares no command, as it does in pnpm v11, so no shim is written for the package; a `directories.bin` entry on the same package is still linked.

- A dependency pinned to an exact version carrying semver build metadata (`"@parcel/codeframe": "2.0.0-canary.1718+d8408010f"`) installs again instead of failing with `ERR_PNPM_NO_MATCHING_VERSION` [#14096](https://github.com/pnpm/pnpm/issues/14096). npm strips build metadata when it publishes a version, so pnpm strips it from the version it looks up, matching npm and pnpm v11.

- A package's `files` entries now match only at the package root, the way npm reads them. A bare `src` used to also match nested directories such as `example/src`, so a dependency installed from git could ship the repository's own example app. The same filter decides what `pnpm pack` and `pnpm publish` put in a tarball and what `pnpm deploy` copies, so those stop carrying the extra files too. Exclusions such as `!**/__tests__` and `!*.map` still match at any depth. A package already in the store keeps its old file set until it is fetched again.

- A `pnpm install --filter <selector>` run that has nothing to do now reports "Already up to date" without entering the install pipeline, the same way an unfiltered `pnpm install` already did [#14033](https://github.com/pnpm/pnpm/issues/14033).

- On Windows, upgrading pnpm no longer leaves a stale `pnpm.ps1` behind. PowerShell resolves `pnpm.ps1` ahead of `pnpm.cmd`, so a shim written by an older installation kept running the previous version. Linking the pnpm CLI's bins now deletes it [#13919](https://github.com/pnpm/pnpm/issues/13919).

- Settings written to a `pnpm-workspace.yaml` block that uses inline (flow) YAML — `catalog: { foo: ^1.0.0 }`, `overrides: { foo: 1.0.0 }`, `minimumReleaseAgeExclude: [foo@1.0.0]` — are now edited in place instead of failing or corrupting the file. `pnpm audit`, `pnpm link`, `pnpm approve-builds`, `pnpm patch`, `pnpm add --config`, and catalog updates all keep the block's flow style, its other entries, and its comments [#14108](https://github.com/pnpm/pnpm/issues/14108).

- A frozen install no longer rewrites the `packageManagerDependencies` block of `pnpm-lock.yaml`. When the pnpm version pinned by `devEngines.packageManager` (or by `packageManager`) is missing from the lockfile or no longer matches it, `--frozen-lockfile` now fails with `ERR_PNPM_FROZEN_LOCKFILE_WITH_OUTDATED_LOCKFILE` instead of resolving the version and saving it, so a manifest whose pin was bumped without regenerating the lockfile can no longer pass CI [#14009](https://github.com/pnpm/pnpm/issues/14009).

- When a git-hosted dependency is blocked from running build scripts, the error now suggests an `allowBuilds` entry that actually approves it. It quoted the bare package name, which never matches a git-hosted package, so following the suggestion left the install failing the same way [#14002](https://github.com/pnpm/pnpm/issues/14002).

- A git dependency installed over HTTPS from a hosted repository now keeps its branch, tag, or version range in the specifier recorded in `package.json`. It was written back without one, so the next `pnpm update` moved the dependency to the repository's default branch [#13999](https://github.com/pnpm/pnpm/issues/13999).

- Added support for the `globalPnpmfile` setting, which names a user-level pnpmfile that runs for every project ahead of the project's own. Like pnpm, it is left out of the lockfile's `pnpmfileChecksum`, so editing it does not decide whether a lockfile is still current. `pnpmfile` and `globalPnpmfile` are now also readable from `PNPM_CONFIG_PNPMFILE` and `PNPM_CONFIG_GLOBAL_PNPMFILE`.

- Fix recursive `pnpm update <name>@<version>` so an exact pinned update stays scoped to the requested version line: copies of the same package on another major line — or, for a `0.x` request, another minor line — keep their locked resolution instead of being re-resolved along with the target.

- Under `nodeLinker: hoisted`, a dependency declared against a peer-resolution variant of a package version is no longer dropped from the installed layout. All variants of a version share one hoisted copy, and edges pointing at any of them now resolve to it, so the depending project keeps the package in its `.package-map.json` and the depending package keeps it in its `node_modules/.bin`.

- A repeat `pnpm install` with `nodeLinker: hoisted` is a no-op again when a workspace package declares the dependencies [#14001](https://github.com/pnpm/pnpm/issues/14001). The hoisted linker installs them into the root `node_modules`, but the up-to-date check previously looked under each package's own `node_modules` and reinstalled the whole tree every time. A hoisted install also no longer reports the packages it just wrote as broken.

- `ignorePnpmfile` can now be set in `pnpm-workspace.yaml` and read from `PNPM_CONFIG_IGNORE_PNPMFILE`, not only passed as `--ignore-pnpmfile`, so a project or a machine can turn pnpmfile hooks off once instead of adding the flag to every command. The flag still applies on top. As in pnpm, the global `config.yaml` cannot set it: a pnpmfile belongs to the project that ships it.

- Fixed pnpm failing to read `.modules.yaml` files containing long dependency paths [#13875](https://github.com/pnpm/pnpm/issues/13875). The manifest is now parsed as JSON (the format pnpm writes it in), falling back to the YAML parser only for manifests written by old pnpm versions.

- `--config.minimum-release-age` is honored again, along with `--config.minimum-release-age-exclude`, `--config.minimum-release-age-ignore-missing-time` and `--config.minimum-release-age-strict`. Each overrides the matching `pnpm-workspace.yaml` setting, and the exclude flag may be repeated to build a list [#13929](https://github.com/pnpm/pnpm/issues/13929).

- An unreadable `node_modules/.modules.yaml` no longer makes `pnpm install` delete `node_modules` and relink every package on each run. The unparsable state file is now reported as an error instead [#14062](https://github.com/pnpm/pnpm/issues/14062).

- `pnpm outdated` and `pnpm update --interactive` now leave out the dependencies listed in `updateConfig.ignoreDependencies`, instead of reporting them and offering them for update.

- Fixed `pnpm outdated` and `pnpm update --interactive` offering versions blocked by `minimumReleaseAge` [pnpm/pnpm#14004](https://github.com/pnpm/pnpm/issues/14004).

- `pnpm pack` writes tar entries in the POSIX ustar header form npm uses — `ustar\0` magic and the explicit `0` regular-file typeflag — instead of the GNU form with a NUL typeflag, which strict tar readers such as publint mistake for the end-of-archive marker [#13924](https://github.com/pnpm/pnpm/issues/13924).

- Fixed `--config.ignore-scripts=true` not being honored by CLI commands such as `pnpm pack` [#13986](https://github.com/pnpm/pnpm/issues/13986).

- `pnpm install <pkg>` now adds the package, the same as `pnpm add <pkg>` and matching the JavaScript CLI. It previously ended in a usage error: `pnpm i valibot` printed `error: unexpected argument 'valibot' found` instead of saving the dependency [#13886](https://github.com/pnpm/pnpm/issues/13886).

- Fixed Plug'n'Play projects to preload `.pnp.cjs` for dependency and project lifecycle scripts, `pnpm run`, and `pnpm exec`. The generated loader now also exposes the public Yarn PnP API surface.

- Workspace packages declared with a parent-relative pattern in `pnpm-workspace.yaml` (`../shared`, `../../docs/*`) are discovered again. They were dropped from the project list, so `pnpm list -r` and `--filter` did not see them and a frozen install of a lockfile that already held their importer entries failed with `ERR_PNPM_PACKAGE_MANAGER_UNSAFE_IMPORTER_PATH`.

- `pnpm pkg get` and `pnpm pkg set` now accept hyphens inside a dot-notation property path, so `pnpm pkg get dependencies.some-package-name` reads the key instead of failing with `ERR_PNPM_UNEXPECTED_TOKEN_IN_PROPERTY_PATH`. The bracketed and quoted forms already worked and are unchanged.

- A path named by the `pnpmfile` setting that is not on disk now fails with `ERR_PNPM_PNPMFILE_NOT_FOUND` and names the file, instead of surfacing as a generic pnpmfile execution failure. Discovery of the default `.pnpmfile.mjs` / `.pnpmfile.cjs` is unaffected: a project that ships neither still installs normally.

- `pnpm remove` now prunes undecided entries (`"set this to true or false"`) from `allowBuilds` in `pnpm-workspace.yaml` when `sharedWorkspaceLockfile: true` and the corresponding packages are removed [pnpm/pnpm#13892](https://github.com/pnpm/pnpm/issues/13892).

- Suggest `pnpm shim add <runtime>` after pinning a project runtime when no project-aware global shim is installed. Explicit project-aware shims now reject unrelated global bin conflicts and are restored after a matching global package is removed or replaced by a version that drops its bin.

- `pnpm -r update --latest --depth 0 <selector>` now fails with `ERR_PNPM_NO_PACKAGE_IN_DEPENDENCIES` when no project in the workspace declares a matching dependency, instead of silently doing nothing.

- Fixed repeat installs paying for a full lockfile comparison forever after a modification-time collision. When a `package.json` was last modified inside the same clock tick that the install recorded as its validation baseline — a fast install, a checkout that copied files with identical timestamps, or any filesystem that keeps only whole-second modification times — the manifest kept reading as possibly-modified, so every later `pnpm install` and `verify-deps-before-run` check re-compared the manifests against the lockfile instead of taking the fast path [#13907](https://github.com/pnpm/pnpm/issues/13907).

- The Rust CLI now honors five settings it recognized but ignored: `updateNotifier`, `legacyDirFiltering`, `initAuthorName` / `initAuthorEmail` / `initAuthorUrl`, `initLicense`, and `initVersion`. `pnpm install` and `pnpm add` check once a day for a newer pnpm and print how to get it (turn it off with `updateNotifier: false`); a `{<dir>}` filter selector can go back to matching the subtree below the directory with `legacyDirFiltering: true`; and `pnpm init` writes the configured author, license, and version into the `package.json` it scaffolds. `PNPM_CONFIG_INIT_VERSION` is now read as well.

  `maxsockets`, npm's spelling of `maxSockets`, is no longer ignored: both spellings are read from `pnpm-workspace.yaml`, the global config file, the environment, and the command line, in that increasing order of precedence — a value passed on the command line now wins even when the two sides spelled the setting differently.

  A `lastUpdateCheck` timestamp dated in the future — after a clock change, a restored snapshot, or a hand-edited state file — no longer silences the update check until that time comes around.

  `legacyDirFiltering` no longer reaches the workspace-root selectors pnpm generates for itself: the `!{<workspace-root>}` exclusion a recursive `run` / `exec` / `add` / `test` appends, and the `{<workspace-root>}` inclusion `--workspace-root` appends. Read as subtree matches they named every project below the root, so a recursive command under the setting selected nothing at all, and `--workspace-root` pulled in every project below the root instead of the root alone [#14101](https://github.com/pnpm/pnpm/issues/14101).

- `pnpm sbom` now honours `--filter-prod`, the full `--filter` selector syntax (dependency queries such as `pkg...`, `{dir}` and glob paths, `[since]` change queries, exclusions), and `--workspace-root`. Selectors that match no project print `No projects matched the filters` and write no SBOM, and `--split` emits its per-project SBOMs in a stable order.

  The universal `--fail-if-no-match` flag is supported too: any filtered command whose selectors match no workspace project now exits with code 1 [#14064](https://github.com/pnpm/pnpm/issues/14064).

- `pnpm sbom` now fails with `ERR_PNPM_SBOM_MISSING_IMPORTERS` when `pnpm-lock.yaml` has no entry for a selected project, instead of writing an SBOM that under-reports that project's dependencies. Previously this crashed with `Cannot read properties of undefined (reading 'devDependencies')`.

- `pnpm self-update` now rewrites a simple `devEngines.packageManager.version` range (`^`/`~`) to the newly installed version, keeping the operator — matching how `pnpm update` and `pnpm runtime set` rewrite ranges. Complex ranges such as `>=8.0.0` that the new version satisfies are still left unchanged [#13935](https://github.com/pnpm/pnpm/issues/13935).

- `pnpm update` now preserves the existing range operator when updating a prerelease dependency. See pnpm/pnpm#7002.

- `pnpm update <name>@<version>` now fails with `ERR_PNPM_UPDATE_VERSION_ON_INDIRECT_DEP` when the package is not a direct dependency of any selected project, instead of quietly updating it to whatever a fresh install would resolve. There is nowhere to record the version in that case, so the request cannot be honored, and the error points at the `overrides` entry that does pin a transitive dependency. Ranges and tags are unaffected, and a package that any selected project declares directly still takes its version as before.

- `trustPolicy: no-downgrade` no longer aborts the install with `ERR_PNPM_MISSING_TIME` on registries that serve no per-version `time` field when `minimumReleaseAgeIgnoreMissingTime` is set. The trust check reads the same publish dates the `minimumReleaseAge` check does, so it now honors the same opt-in and skips the affected package with a warning [#12446](https://github.com/pnpm/pnpm/issues/12446).

  `minimumReleaseAgeIgnoreMissingTime` no longer lets a lockfile entry the registry does not list pass the `minimumReleaseAge` check during lockfile verification. The opt-in covers a registry that cannot date its releases; a packument that does date every version it lists is saying it never published this one, which stays a hard failure.

  The missing-`time` warning now names the check it is reporting on, so a package whose `minimumReleaseAge` and `trustPolicy` checks are both skipped warns about both instead of only the first.

- `pnpm update <pkg>@<tag>` now saves the version the dist tag resolved to in `package.json`, keeping the range operator the dependency already declared, instead of saving the tag itself. A dependency declared through a `catalog:` reference, a `workspace:` or `npm:` alias, or a path or git specifier keeps its declaration, and one that already tracks a dist tag records the tag asked for [#14092](https://github.com/pnpm/pnpm/issues/14092).

- `pnpm update <pkg>@<version>` now updates only the selected packages and leaves unrelated dependencies unchanged. A selector that renames the package it installs — `pnpm update <alias>@npm:<pkg>@<version>` or the `jsr:` equivalent — now targets the package the alias installs rather than the alias.

- `pnpm version <bump>` with `--dry-run` no longer edits `package.json` files. It now only reports the bumps it would make, and skips the working tree check, the version lifecycle scripts, the commit, and the tag [`pnpm/pnpm#13953`](https://github.com/pnpm/pnpm/issues/13953).

- On Windows, `pnpm store path` now returns a conventional drive path without the `\\?\` verbatim prefix when the project and pnpm home are on different drives [#13987](https://github.com/pnpm/pnpm/issues/13987).

## 12.0.0-rc.8

### Minor Changes

- `packageImportMethod: auto` now tries hardlinks before cloning on Linux. A reflink materializes a new inode and copies extent bookkeeping inside the filesystem's metadata trees, where a hardlink is one directory entry — on btrfs this roughly halves the time an install spends materializing `node_modules` from a warm store. ext4 installs are unchanged (cloning was never supported there, so `auto` already hardlinked), and macOS keeps clone-first, where APFS `clonefile` is the platform's cheap primitive. Cloning remains the fallback when the store refuses hardlinks, and remains available explicitly via `packageImportMethod: clone`.

  This ships with pnpm 12 only: pnpm 11's importer deliberately keeps clone-first, since changing what the default materializes on disk is not a point-release change.

### Patch Changes

- `pnpm approve-builds` now removes `onlyBuiltDependencies`, `onlyBuiltDependenciesFile`, `neverBuiltDependencies`, and `ignoredBuiltDependencies` from `pnpm-workspace.yaml` when it writes `allowBuilds`. Those settings were replaced by `allowBuilds` in pnpm 11 and silently ignored since, so a workspace migrated from pnpm 10 kept them around looking active.

- `pnpm audit` no longer reports a patched version that was never published or is deprecated. The inferred patched range (e.g. `>=4.17.24` from `<=4.17.23`) is now checked against the registry packument, and the report is corrected to the lowest non-deprecated published version that satisfies it (e.g. `>=4.18.1` when `4.17.24` does not exist and `4.18.0` is deprecated). When no published version satisfies the range, the report shows `Patched versions: None`. This also prevents `pnpm audit --fix` from adding overrides or `minimumReleaseAgeExclude` entries for patches that do not exist [#13824](https://github.com/pnpm/pnpm/issues/13824).

  `pnpm audit --fix` and `pnpm audit --fix update` no longer add a `minimumReleaseAgeExclude` entry when the registry packument shows that the minimum patched version was never published. Previously such entries were written for versions that do not exist, which would have let a later publish of that version bypass the `minimumReleaseAge` gate [#11563](https://github.com/pnpm/pnpm/issues/11563).

  The `--json` output of `pnpm audit` now returns `patched_versions: null` for advisories whose inferred patch is not available (never published, skipped, yanked, or deprecated), making it easier for tooling to distinguish "no fix available" from "fix available at version X".

- Re-fetch full registry metadata when `minimumReleaseAge` is enabled and an abbreviated packument's `time` map omits timestamps for some versions. This prevents mature versions from being filtered out and resolution from falling back to the lowest matching version [pnpm/pnpm#13741](https://github.com/pnpm/pnpm/issues/13741).

- A config dependency carrying an inline integrity (the `<version>+<integrity>` form, or the object form without a `tarball`) now takes its tarball URL from the registry's packument instead of deriving it from the registry URL, so migrating one costs an extra metadata request. On a registry that serves tarballs from a path pnpm cannot derive, GitLab's group endpoint for one, installing such a config dependency failed with a 404 while the same package installed fine as a regular dependency [#13765](https://github.com/pnpm/pnpm/issues/13765).

- Don't treat files like `license16.json` as a package license when deciding if the workspace LICENSE file should be included in the packed package.

- Reduced warm update overhead by limiting virtual-store bin linking and ignored-script build bookkeeping to packages materialized by the current install.

- `pnpm init` now pins the exact pnpm version instead of a `^` range, and records it in the `packageManager` field alongside `devEngines.packageManager`. Corepack reads only `packageManager` and accepts nothing but an exact version, so it rejected the generated `package.json` with "expected a semver version" [pnpm/pnpm#13969](https://github.com/pnpm/pnpm/issues/13969). A package created inside an existing workspace is still left unpinned — it follows the pin at the workspace root — and `--no-init-package-manager` still scaffolds a manifest without any pin. In pnpm 12, `pnpm init` also honors `initType` and its `--init-type` flag, so the manifest it writes is the same one pnpm 11 writes.

- `node-linker=hoisted` installs no longer produce broken layouts on graphs with version conflicts. Three hoister fixes, aligning with `@yarnpkg/nm` (which the TypeScript CLI delegates to):

  - A version-conflicted package depended on by several packages kept its conflicting transitive dependencies under only one of the dependents, so requiring them through any other dependent resolved the wrong (root-hoisted) version — for example an ESM `parse-entities@4` resolving `character-entities-legacy` v1 instead of v3, which crashes with `ERR_IMPORT_ATTRIBUTE_MISSING` on Node.js 22. Hoist decisions are now made per parent path on decoupled copies (ports upstream's `decoupleGraphNode`).
  - Peer-resolution variants of one package version now collapse onto a single copy (ports pnpm v11's `depPathByPkgId` mapping) instead of conflict-nesting a copy under every dependent — on peer-variant-heavy graphs (such as `bit`'s) the old behavior also made the per-path walk explode.
  - Hoisting no longer shadows names a subtree resolves through an ancestor directory: a candidate is refused when a nearer ancestor holds a different version of its name (upstream's "filled by parent" scan) or when the hoist root's subtree already resolves that name from above (upstream's `usedDependencies` gate).

- `pnpm update --no-save <pkg>@<version>` now keeps the manifest's declared importer specifier in `pnpm-lock.yaml` when the requested version satisfies that range, so a subsequent `--frozen-lockfile` install no longer fails because the lockfile records the requested version as the specifier.

- Reduced registry metadata requests during dependency resolution by reusing cached metadata when lockfile preferences prove that no uncached version can win [pnpm/pnpm#13976](https://github.com/pnpm/pnpm/issues/13976).

- Improved install performance: the store-index writer's shutdown now overlaps the install's final lockfile and `.modules.yaml` writes instead of extending the install's tail.

- A setting in the global `config.yaml` that pnpm does not read from that file, or that is written in kebab-case instead of camelCase, is now reported instead of being ignored silently.

- A forced full re-resolution (config changes the fast lockfile update cannot absorb, such as a changed override or `packageExtensions`) no longer moves dependencies whose recorded versions still satisfy their ranges. The prior lockfile now pins each still-satisfied edge even when its recorded subtree cannot be reused wholesale, so open ranges like `@types/node: "*"` keep their locked versions instead of collapsing onto the highest locked version and churning the lockfile.

- Improved fresh resolution performance when package metadata is already cached.

- Improved fresh installs by reusing the store index and verified-files cache during dependency materialization.

- A runtime installed through `devEngines.runtime` now matches the host when `supportedArchitectures` lists several platforms. Listing `os: [darwin, linux]` and `cpu: [x64, arm64]` used to install the runtime built for the first entry of each list, so a machine running Linux on arm64 got a macOS x64 Node.js that could not execute [#13898](https://github.com/pnpm/pnpm/issues/13898).

- `pnpm self-update <tag>` no longer downgrades when the dist-tag points at the pnpm version already running and that version is younger than `minimumReleaseAge`. The maturity cutoff moved the tag back to the previous mature release, so `pnpm self-update next-12` on v12.0.0-rc.4 switched to v12.0.0-rc.3.

- Improved install performance: large tarballs are now verified and extracted while they download, so the biggest packages — whose downloads finish last — no longer add their whole extraction to the end of the install.

## 12.0.0-rc.7

### Minor Changes

- `node_modules/.modules.yaml` no longer records the registries an install resolved from, and the recorded copy is dropped from the file on the first install that rewrites it.

  It dated from the lockfile format that spelled a dependency's path relative to its registry, where reading an installed tree meant knowing the registries it was installed with. Dependency paths have not carried a registry for several major versions, and the recorded copy outlived its use: `pnpm list`, `pnpm why`, and single-project installs preferred it over the project's own configuration, so a project whose registry had changed since its last install was still read through the old one.

  They now use the configured registries, like every other command already did.

- When `enableGlobalVirtualStore` is on, every process pnpm spawns for the project (`pnpm run`, `pnpm exec`, lifecycle scripts) now receives a `NODE_PATH` pointing at the project's hoisted `node_modules`, plus a `NODE_OPTIONS` `--import` flag that registers a resolve hook restoring `NODE_PATH` lookups for ESM imports. Dependencies that import undeclared ("phantom") packages keep resolving under the global virtual store — for both CommonJS and ESM — without installing the `@pnpm/plugin-esm-node-path` config dependency [pnpm/pnpm#9618](https://github.com/pnpm/pnpm/issues/9618). Tools run by `pnpm dlx` resolve such dependencies too: the JS CLI passes them the same environment, while the Rust CLI's dlx cache is self-contained, so its layout already exposes them.

- A registry can now declare that its abbreviated metadata carries the `time` field, so `resolutionMode: time-based` reads the full metadata document only from the registries that need it:

  ```yaml
  resolutionMode: time-based
  registries:
    https://npm.internal.example/:
      supportsTimeField: true
  ```

  `registry.npmjs.org` omits `time` from abbreviated metadata, so a time-based resolution has to fall back to the much larger full document. That fallback used to be all-or-nothing: `registrySupportsTimeField` answered for every registry at once, so a project resolving from both the public registry and a Verdaccio instance either paid for full metadata everywhere or claimed a `time` field npmjs does not serve. The answer is now per registry, and `registrySupportsTimeField` remains the answer for every registry that does not declare one.

  The declaration is also sent to a pnpr server, which applies it to the resolution it runs on the client's behalf.

- A pnpr resolve request now carries the client's registries the way the `registries` setting declares them — keyed by URL, with the scopes routed to each, the bare-specifier prefix each answers to, and each one's `serverType` — in place of the prefix map it used to send.

  The server routes them through the same inversion the config reader runs, so a pnpr-served install resolves a scoped dependency from the registry that scope is routed to, which it previously could not: only the default registry and the prefix-addressed ones reached the server. A declared `serverType` reaches it too, so the tarball URLs pnpr omits from the lockfile match the ones the client reconstructs.

  Built-in scope routes the project has not pointed elsewhere are not declared, so a pnpr server's allowlist is not asked about `npm.jsr.io` on requests that resolve no JSR package.

  A registry a request only declares is no longer refused up front for being off the server's allowlist — a client describes its whole configuration, including scopes a given resolve never reaches, so a stray `@scope:registry` in a developer's `~/.npmrc` no longer fails every install against a pnpr server that does not serve it. The boundary moves to the fetch itself: an origin the resolve does reach is refused before the request leaves the server, with the same message.

  This changes the resolve and verify-lockfile request bodies. A pnpr server and its clients have to be on matching versions; the protocol is still experimental and unversioned.

- A resolve request now carries the client's `resolutionMode`, so an install delegated to a pnpr server picks versions the way the client would. `time-based` and `lowest-direct` reached the server as nothing at all, leaving it on its `highest` default: the returned lockfile pinned the highest satisfying version of every dependency, and the setting appeared to be ignored.

  This adds a field to the resolve request body. A server older than its client ignores it and keeps resolving `highest`; the protocol is still experimental and unversioned.

- The `registries` setting now declares a registry once, keyed by its URL, with everything about that registry in the entry: how it lays out tarball URLs, the scopes routed to it, and the bare-specifier prefix it answers to.

  ```yaml
  registries:
    https://artifactory.example.com/artifactory/api/npm/npm-virtual/:
      serverType: artifactory
      scopes: ['@acme', '@acme-internal']
      prefix: work
  ```

  - **`serverType`** tells pnpm how the registry lays out its tarball URLs, which decides whether a URL can be omitted from `pnpm-lock.yaml`:
    - **undeclared** (the default) — strict. Only the exact canonical URL is treated as reconstructible.
    - **`npm`** — the registry behaves like `registry.npmjs.org`, which also serves a scoped package from its percent-encoded path. Declare this for a faithful mirror or caching proxy of the public registry so its tarball URLs can be omitted too.
    - **`artifactory`** — JFrog Artifactory repeats the scope in a scoped package's tarball filename (`@acme/widget/-/@acme/widget-1.0.0.tgz`) where the npm registry strips it (`@acme/widget/-/widget-1.0.0.tgz`). Declaring it lets pnpm rebuild that URL, so it is omitted from `pnpm-lock.yaml` instead of being written out for every scoped package [pnpm/get-npm-tarball-url#16](https://github.com/pnpm/get-npm-tarball-url/issues/16).
  - **`scopes`** lists the `@`-prefixed scopes that resolve from this registry. A bare `'@'` is the scope-less default registry, the one the `registry` setting names.
  - **`prefix`** is the alias a dependency addresses this registry by, as in `"foo": "work:^1.0.0"`.

  The layout is never inferred from the registry URL, so nothing changes unless you declare it; `registry.npmjs.org` continues to behave as `npm` without being declared. Because the lockfile depends on `serverType`, it is read from `pnpm-workspace.yaml` only — a `serverType` in the global `config.yaml` is ignored, so one developer's machine cannot shape a lockfile their collaborators read back with a different layout. Credentials are rejected in this setting, in a key as well as in a field, and still belong in `.npmrc`. An entry that routes nothing to itself and matches no configured registry is reported as a warning rather than silently ignored.

  ### Migrating

  The older `registries` shape, a map of `<scope>: <url>` strings, still works and needs no change:

  ```yaml
  registries:
    '@acme': https://npm.acme.example/
  ```

  `namedRegistries` is deprecated in favor of the `prefix` field, and is still read for prefixes `registries` does not declare.

  `toLockfileResolution` and `isCanonicalRegistryTarballUrl` now take their registry and layout as an options object rather than positional arguments, so `@pnpm/lockfile.utils` and `@pnpm/resolving.tarball-url` get a major bump.

- Added `virtualStoreType`, which names where the virtual store lives — one store per machine, or one per project:

  ```yaml
  virtualStoreType: global   # or: project
  ```

  It is the canonical spelling of `enableGlobalVirtualStore`, which keeps working. When a project sets both, `virtualStoreType` wins. It can also be set through `PNPM_CONFIG_VIRTUAL_STORE_TYPE` and read back with `pnpm config get virtualStoreType`. The default is unchanged — `project`, so the shared store stays opt-in.

  The setting is independent of `nodeLinker`. `isolated` and `pnp` both work with either store type, and `hoisted` writes no virtual store at all, so it is unaffected.

### Patch Changes

- Fixed `pnpm patch-commit` in project and edit paths containing non-ASCII characters.

- Fixed `404` errors when installing from a registry that serves scoped packages only from a percent-encoded path, such as GitHub Enterprise Server. Outside `registry.npmjs.org`, a tarball URL that encodes the scope separator as `%2f` or `%2F` is no longer mistaken for one that pnpm can rebuild from the package name, version, and registry, so it is kept in `pnpm-lock.yaml` and requested verbatim on the next install [#13534](https://github.com/pnpm/pnpm/issues/13534).

- Fixed an inconsistency where `minimumReleaseAgeExclude` (and `trustPolicyExclude`) wildcard/bare-name rules behaved differently in the evaluator and normalizer. A bare rule now consistently evaluates as matching every version, preventing unexpected behavior and silent widening of version policy exemptions when pnpm rewrites the workspace manifest [pnpm/pnpm#13725](https://github.com/pnpm/pnpm/issues/13725).

- Fixed `pnpm update --global --latest` failing with a 404 error when a globally installed package was not added from the registry by name. Packages installed from a local path (`link:`/`file:`), a git repository, a tarball URL, an `npm:` alias, or a named registry now keep their spec during a global update instead of being looked up by name in the default registry. See pnpm/pnpm#12854.

- `pnpm outdated` and `pnpm update --interactive` now dereference `catalog:` specifiers before querying the registry. A catalog entry that is an npm alias (`'@types/zkochan__table': npm:@types/table@6.3.2`) no longer fails with `ERR_PNPM_OUTDATED_REGISTRY_ERROR` for the alias key, and `pnpm outdated --compatible` compares against the range the catalog holds instead of skipping the dependency.

- A failed packument request now reports the status the registry returned (`404 Not Found`) instead of "error decoding response body".

- Installs with a cold cache are significantly faster: lockfile verification no longer delays resolution or downloads and re-checks far less data over the network, and downloaded packages are linked while the remaining downloads are still in flight.

- Fixed `pnpm` installs using pnpr to honor the client's `autoInstallPeers`, `dedupePeers`, and `excludeLinksFromLockfile` settings [pnpm/pnpm#13389](https://github.com/pnpm/pnpm/issues/13389).

- The three registry lookups are now named for what they are keyed by, so that none of them is called `registries` — a name the `registries` setting itself has taken:

  | before | after |
  |---|---|
  | `Config.registries` | `Config.registriesByScope` |
  | `Config.namedRegistries` | `Config.registriesByPrefix` |
  | `Config.registryOptions` | `Config.registryOptionsByUrl` |

  The same rename applies to the `RegistryContext` fields, the `Registries` and `NamedRegistries` types (now `RegistriesByScope` and `RegistriesByPrefix`), `normalizeRegistries` / `normalizeNamedRegistries` (now `normalizeRegistriesByScope` / `normalizeRegistriesByPrefix`), and the `BUILTIN_NAMED_REGISTRIES` constant (now `BUILTIN_REGISTRIES_BY_PREFIX`).

  This is an internal rename: no setting, error code, lockfile field, or `.pnpmfile.cjs` hook field changes. A `preResolution` hook still reads `ctx.registries`, which is the name pacquet passes as well. The `registries` and `namedRegistries` settings are read under the names users write them.

  The pnpr resolve request sends `registriesByPrefix` where it sent `namedRegistries`. A pnpr server and its clients must be on matching versions, which is already the case for an experimental server.

- An install that had to re-hash store files to verify them now reports it. If that cost more than a second, it says how long — `The integrity of N files was checked in 2.5s.` — and if it was quick but covered more than a thousand files, it names the cause instead: their timestamps changed since the store recorded them, which a backup tool, an antivirus scan or a copied store can do.

- Installs are faster in workspaces that declare inter-workspace dependencies with plain ranges (`"*"`, `"^1.2.3"`) rather than the `workspace:` protocol. With `preferWorkspacePackages` enabled, linking such a dependency no longer makes a registry request that cannot change the outcome — and workspace packages that were never published no longer cost a 404 on every install.

- An override change is now absorbed by the fast lockfile update even when another, unchanged override uses the `catalog:` protocol. Previously any `catalog:`-valued override forced a full re-resolution whenever the override list changed, which could move unrelated packages in the lockfile (for example after `pnpm audit --fix` added an override).

- Reduced peak memory usage when installing large packages. A tarball whose compressed size is at least 16 MiB, or whose registry-reported unpacked size is at least 64 MiB, is now extracted by streaming the decompression directly into the content-addressable store instead of materializing the whole decompressed archive in memory, and its large files are hashed and written to the store incrementally.

- `pnpm why` and `pnpm list` no longer print stray `[90m`-style codes in their trees when the terminal supports colors. The bolded labels — the searched package in `pnpm why`, the project header and the matched package in `pnpm list` — dropped the escape byte of the styles they already carried, leaving the color codes as visible text.

## 12.0.0-rc.6

### Minor Changes

- Added `pnpm cache path`, which prints the directory pnpm uses for its metadata cache. CI setups can use it to cache that directory — including the lockfile verification log, which lets a job skip re-checking an unchanged lockfile against the configured supply-chain policies.

- pnpm installs the other package managers now, not just itself: npm, Yarn Classic, Yarn Berry, Yarn 6 (`yarnpkg/zpm`), and Bun. Each is resolved and fetched through the trusted package-manager registries, and an npm-published one is verified against npm's signature for its exact version before it is executed.

  Three things use it:

  - A git-hosted dependency is prepared with the package manager it asks for. Its `packageManager` / `devEngines.packageManager` pin is honored, and a `yarn.lock` written by Yarn Classic no longer gets installed by Yarn Berry. pnpm provides that package manager when the dependency pinned a version, or when the host cannot satisfy what the dependency needs — so a repository built with Yarn now installs on a machine that has only pnpm, while a host that already has a suitable one keeps using its own.
  - `pnpm dlx` (`pnx`) runs one of them for a single command: `pnx yarn@4 install`, `pnx npm@11 ci`, `pnx bun@1.3.0 install`. Naming a package manager, or a runtime (`node`, `deno`, `bun`), there now provisions the real thing instead of installing the npm package that shares its name — unless the specifier locates a package rather than asking for a released version (`pnx yarn@npm:yarn@1.22.22`, `pnx yarn@yarnpkg/berry`), which installs what it names — `pnx yarn@4` was previously a missing version, since Yarn 4 is published as `@yarnpkg/cli-dist`, and `pnx node@22` now runs that Node.js release rather than a wrapper that downloads one. `--package` naming a package manager picks which of its commands to run, so `pnx --package npm@11 npx create-something` runs that npm's `npx`.
  - `pnpm shim add yarn` links a `yarn` command that runs whatever version the current project pins, and `pnpm shim rm` / `pnpm shim ls` manage those shims. It works for any package, not only package managers. Shims are never created as a side effect of `pnpm setup` or an install — a shim shadows the rest of your `PATH`, so pnpm only writes one when asked.

  Installing a package manager globally (`pnpm add -g yarn`) now makes it follow a project's pin too, the way a globally installed Node.js already follows `devEngines.runtime`: the pinned version runs where a project pins one, and the globally installed copy is the fallback everywhere else. An explicit `globalShims` entry, including `false`, is left as you set it.

  `pnpm add` follows the same rule about what a name means. `pnpm add -g yarn@4` installs Yarn Berry — it used to fail, because npm's `yarn` package stops at Classic — and `pnpm add -g node@22` / `pnpm add -g deno@2` install that Node.js or Deno release rather than a wrapper package that downloads one. In a project, naming a package manager records which one the project uses instead of installing it as a dependency, and naming a runtime records it under `engines.runtime` as `node@runtime:22` already did.

  The declaration goes where the package manager reads it. Yarn is started from a project pin by corepack, which reads only `packageManager` and only accepts an exact version there, so `pnpm add yarn@4` resolves the line and writes `"packageManager": "yarn@4.18.0"` — the same thing `corepack use yarn@4` writes, down to the `+sha512.…` integrity for the Yarn Classic line that corepack pins its tarball with. Every other package manager is recorded in `devEngines.packageManager`, which holds a range. Only one of the two fields is ever left behind: they declare the same thing, and corepack refuses to run a project whose declarations disagree.

  A JavaScript package manager on a machine without Node.js gets a managed LTS runtime to run on.

  What changes for a project coming from v11: `pnpm add yarn` records the project's package manager instead of installing the npm package that shares the name (that package is still reachable as `pnpm add yarn@npm:yarn@1.22.22`), `pnpm add -g yarn` installs the current Yarn line rather than Classic, `pnpm add -g node` / `pnpm add -g deno` and `pnx node` / `pnx deno` install a Node.js or Deno release rather than a wrapper package, and a globally installed package manager defers to a project's pin where there is one.

- Resolving a Node.js runtime version (`devEngines.runtime` / `runtime:` specifiers) is now much faster: the per-version release metadata is cached in the pnpm cache directory after its signature is verified, and an exact stable version such as `runtime:22.23.2` no longer downloads the Node.js release index. A pinned runtime whose metadata was fetched once resolves without any network access, which removes the noticeable delay on the first `node` invocation in a project pinning an already-downloaded runtime [#13899](https://github.com/pnpm/pnpm/issues/13899).

### Patch Changes

- Corepack can run pnpm 12 again [#13018](https://github.com/pnpm/pnpm/issues/13018). Corepack installs no dependencies and runs no lifecycle scripts, so the native binary that the `pnpm` package normally receives from its platform-specific optional dependency was never there, and `corepack use pnpm@next-12` failed with `MODULE_NOT_FOUND`. The package now ships the `bin/pnpm.mjs` and `bin/pnpx.mjs` entry points Corepack looks for; they fetch the pinned native binary on first use — verified against npm's signature and checksum, honouring `COREPACK_NPM_REGISTRY` and the rest of Corepack's registry environment — and hand over to it. Installing pnpm with a package manager is unaffected and still runs the binary directly, with no Node.js startup in between.

- With a configured `pnprServer`, `pnpm install` skips the server exchanges it does not need, closing the gap where an up-to-date project paid a full resolve round trip that a direct install answered locally [pnpm/pnpm#13904](https://github.com/pnpm/pnpm/issues/13904):

  - The repeat-install "Already up to date" fast path now runs with a pnpr server configured.
  - An install whose `pnpm-lock.yaml` still satisfies every manifest skips the server resolve exchange and materializes `node_modules` from the on-disk lockfile.
  - The input-lockfile verification round trip is skipped when the local `lockfile-verified.jsonl` cache already covers the lockfile under the current policy; server-verified and server-resolved lockfiles are now recorded into that cache.
  - Changing the `trustPolicy*`, `minimumReleaseAgeStrict`, or `minimumReleaseAgeExclude` settings now invalidates the repeat-install fast path, matching the TypeScript CLI's workspace-state check.

- The published packages now ship a `THIRD-PARTY-NOTICES.md` file carrying the BSD 2-Clause license of the Yarn code that pnpm's hoisted-layout algorithm and built-in package-compatibility database are derived from.

## 12.0.0-rc.5

### Minor Changes

- **Breaking change.** Dependency cycles are now broken canonically during peer resolution: the members of each cycle are ordered by package id, and the edges that close a cycle are always cut at the same place, no matter where the installation walks into the cycle from. Previously the cut depended on the walk path, so installing the same dependencies could produce different lockfiles depending on importer order or resolution order [#13846](https://github.com/pnpm/pnpm/issues/13846), and a peer-resolution verdict computed for one occurrence of a cyclic package could be wrongly reused at another [#13865](https://github.com/pnpm/pnpm/issues/13865).

  With canonical cycle breaking the lockfile is a pure function of the dependency graph: repeated installs, reordered importers, and reordered dependencies all produce byte-identical lockfiles. Peer dependencies of packages inside a cycle keep nearest-wins resolution along the canonical order, and a dependency edge that closes a cycle references an occurrence of its target resolved at the importer level. On large cycle-heavy workspaces peer resolution is 2–3× faster, uses about 25% less memory, and produces a substantially smaller lockfile (fewer redundant peer variants).

  Existing lockfiles keep working: headless (`--frozen-lockfile`) installs consume them unchanged, and installs that skip resolution leave them untouched. The first install that actually re-resolves (for example after a dependency change) re-keys walk-order-dependent peer variants of cyclic packages once.

### Patch Changes

- Auto-installed peer dependencies are no longer resolved to their lowest satisfying versions under `resolutionMode: lowest-direct` or `time-based`. A hoisted peer is not a dependency the project declares, so it resolves like a transitive dependency — to the highest version satisfying the peer range (under `time-based`, the highest within the publish-date cutoff) [#13871](https://github.com/pnpm/pnpm/pull/13871).

- The resolved dependency graph and lockfile no longer depend on the order in which workspace projects are listed or discovered: importers are processed in project-id order, so reordering the `packages` globs in `pnpm-workspace.yaml` (or any other change to project listing order) produces a byte-identical lockfile [#13846](https://github.com/pnpm/pnpm/issues/13846). This also makes auto-installed peer placement, deprecation-warning attribution, and cycle back-edge bindings a function of the project set alone.

- Fixed non-deterministic lockfiles on cold installs of projects with cyclic peer dependencies: resolved peer variants could silently drop from the lockfile depending on traversal order [#13846](https://github.com/pnpm/pnpm/issues/13846), [#13865](https://github.com/pnpm/pnpm/issues/13865).

- A lockfile entry whose resolution is unchanged no longer loses its recorded `deprecated` marker when a registry serves the package's metadata inconsistently — re-resolving to the same version keeps the deprecation instead of silently dropping the line [#13846](https://github.com/pnpm/pnpm/issues/13846).

- `pnpm update` now writes the new version range back to `package.json` (and to the `catalog:` entry a dependency points at), instead of only updating the lockfile [#13879](https://github.com/pnpm/pnpm/issues/13879). The range operator the dependency already declared is preserved, and a dependency declared through a dist-tag (`"foo": "latest"`) keeps tracking the tag under both `pnpm update` and `pnpm update --latest`.

## 12.0.0-rc.4

### Minor Changes

- Added a new setting `minimumReleaseAgeExcludePrune`. When enabled, `pnpm add`, `pnpm update`, and `pnpm remove` prune the entries of `minimumReleaseAgeExclude` in `pnpm-workspace.yaml` that the freshly written lockfile no longer resolves: versions that are gone are dropped (an entry is removed once none of its versions remain), and entries for packages that are no longer in the lockfile are removed too. Name patterns (`@scope/*`) are always kept. The cleanup is skipped when the install's lockfile does not cover the whole workspace (`sharedWorkspaceLockfile: false`), since entries another project still needs would look stale.

  Renamed `cleanupUnusedCatalogs` to `catalogPrune`, so that catalog pruning and release-age exclude pruning use one vocabulary. `cleanupUnusedCatalogs` continues to work; when both are set, `catalogPrune` wins.

- Added support for the `syncInjectedDepsAfterScripts` setting. It names the scripts after which every injected copy of the package that ran them is brought back in step with its source, so a build script no longer leaves the copies in the virtual store holding stale files.

### Patch Changes

- `pnpm add` no longer re-resolves the dependency graph when `pnpm-lock.yaml` already holds a version satisfying the request — promoting a transitive dependency to a direct one, or adding to a second workspace package what a first one already depends on, now only saves the dependency in `package.json` and records its importer entry. A satisfying locked version is necessary but not sufficient: the install still falls back to a full resolution for a dist tag, an alias, a `workspace:`/`catalog:`/git/tarball specifier, `--save-peer`, an overridden package, a `catalogMode` other than `manual`, and — under `resolutionMode: time-based` or `lowest-direct`, which resolve a direct dependency to the low end of its range — a range several locked versions satisfy.

- Global installs now switch over atomically. The command shims in the global bin directory point at a stable per-package link rather than at the directory a particular install produced, so `pnpm add -g` and `pnpm update -g` activate a new version by moving that one link instead of rewriting every shim. A command can no longer be missing from `PATH` while an install is in progress, and a failed install leaves the previous version in place.

- `pnpm audit --fix` and `pnpm audit --fix update` no longer add `minimumReleaseAgeExclude` entries for patched versions that were published before the `minimumReleaseAge` cutoff. The publish time of each minimum patched version is now checked against the registry metadata, and only versions young enough to be blocked by the age gate get an exclusion entry [#11563](https://github.com/pnpm/pnpm/issues/11563).

- Bounded the number of requests in flight to the `.pnpmfile.cjs` worker process. An install that runs the `readPackage` hook for thousands of packages at once no longer risks failing with `ERR_PNPM_PNPMFILE_FAIL` on a hook timeout spent waiting in the queue rather than running the hook, and holds fewer copies of the manifests it is hooking while it waits.

- `pnpm add <pkg>@<version>` and `pnpm update <pkg>@<version>` under `catalogMode: strict` no longer fail with `ERR_PNPM_CATALOG_VERSION_MISMATCH` when the catalog entry is a range that the wanted version satisfies. The dependency keeps using the catalog; only a version that really falls outside the catalog's range is rejected [#13715](https://github.com/pnpm/pnpm/issues/13715).

- Fixed `pnpm install` in CI to use frozen lockfile mode by default when an existing `pnpm-lock.yaml` is non-empty. An outdated lockfile now fails without being rewritten, while projects without a lockfile or with an empty lockfile can still generate one.

- A changed `catalogs` or `pnpm.overrides` block no longer has to be the only change for `pnpm install` to update the lockfile in place. Editing an override while also removing a dependency, or changing a catalog entry in the same commit as a range bump, is now absorbed in one pass instead of re-resolving the whole dependency graph [#13799](https://github.com/pnpm/pnpm/issues/13799).

  Fixed the lockfile an in-place override update wrote when the overridden package was also a catalog entry: the entry kept the version it had before the override moved the package. The same could happen in reverse, when a catalog entry moved a package an override pins. Both cases now re-resolve instead.

- `pnpm install` now updates the lockfile in place even when several kinds of changes happened since the last install — for example a removed dependency together with a widened `ignoredOptionalDependencies` list, or a dependency edit alongside a patch or settings change. Previously any combination of changes forced a full re-resolution [#13763](https://github.com/pnpm/pnpm/issues/13763).

- Resolving peer dependencies in a workspace whose dependency graph contains many peer-dependency cycles now needs less than half the memory and finishes about twice as fast. Verdicts computed inside dependency cycles are now cached and reused for the occurrences they are provably valid for, instead of being recomputed for every occurrence.

- `pnpm install` and `pnpm dedupe` no longer eat all the available memory while resolving a graph in which many packages declare the same missing peer dependency, such as the `react` peer the `@radix-ui` packages share [#13786](https://github.com/pnpm/pnpm/issues/13786).

- With `dedupeDirectDeps`, a project's symlink that becomes redundant — because the workspace root started providing the same dependency at the same resolution — is removed on the next install instead of surviving forever [#13775](https://github.com/pnpm/pnpm/issues/13775). The layout no longer depends on install history: an incremental install now ends up with the same `node_modules` a clean install of the same manifests produces.

- `pnpm deploy` injects workspace dependencies again, so the deploy directory is self-contained instead of symlinking back into the source workspace [#13754](https://github.com/pnpm/pnpm/issues/13754). Enabling `injectWorkspacePackages` with `dedupeInjectedDeps` disabled now also rewrites already-linked workspace dependencies to injected copies.

- `pnpm deploy --no-optional` no longer writes a lockfile whose snapshots reference optional dependencies that the deploy excluded.

- `pnpm --filter . deploy` deploys the project in the current directory instead of the projects nested under it, so deploying the workspace root now copies the root project and installs its workspace dependencies [#13758](https://github.com/pnpm/pnpm/issues/13758). `pnpm deploy --legacy` no longer rewrites the source workspace's `pnpm-lock.yaml`.

- Fixed `pnpm install` writing a different `pnpm-lock.yaml` for an unchanged project depending on the order its dependencies happened to resolve in, which showed up as spurious lockfile diffs between installs.

- Removing the last dependency that references a catalog entry via the fast lockfile update no longer leaves the stale catalog entry in `pnpm-lock.yaml`.

- `--frozen-lockfile` no longer rejects a lockfile pnpm just generated when `packageExtensions` adds a peer dependency to a workspace project. The peer is auto-installed and recorded in the importer entry, but the freshness check compared against the `package.json` on disk, which has no such peer, and reported the entry as a removed dependency [#13836](https://github.com/pnpm/pnpm/issues/13836).

- A git dependency whose clone (or shallow fetch) fails now reports which package it belongs to, under the `ERR_PNPM_GIT_FETCH_FAILED` code, with credentials in the repository URL redacted. When the lockfile records an SSH remote, the error also explains that fetching it needs an SSH key for that host, and that a lockfile entry written before pnpm v11.21 can be re-recorded over HTTPS with `pnpm update <package>` [#13743](https://github.com/pnpm/pnpm/issues/13743).

- An `integrity` recorded on a git dependency's resolution (`resolution: {type: git, repo, commit, integrity: sha512-…}`) is no longer treated as a checksum. pnpm never verifies a git checkout against such a hash — the commit pins the content — so it is now dropped when the lockfile is rewritten, and `pnpm sbom` no longer republishes it as a CycloneDX/SPDX checksum. Lockfiles carrying one also load again instead of failing with `ERR_PNPM_BROKEN_LOCKFILE` [#13042](https://github.com/pnpm/pnpm/issues/13042).

  `pnpm sbom` now also publishes the checksum of a `type: binary` runtime archive, which pnpm does verify.

- A git dependency whose `git ls-remote` fails now reports the `ERR_PNPM_GIT_RESOLVE_FAILED` code, naming the dependency instead of printing a bare `git` invocation, with credentials in the repository URL redacted. A specifier that does not ask for SSH resolves over HTTPS, because the URL recorded in the lockfile has to work on every machine that installs it, so the error explains how to substitute the transport on a machine that can only reach the host over SSH (`git config --global url."git@<host>:".insteadOf "https://<host>/"`) [#13743](https://github.com/pnpm/pnpm/issues/13743).

  A missing `git` executable is reported as one, instead of surfacing the raw failure to start the process.

  Credentials embedded in a git specifier are redacted from the "Could not resolve \<ref\> to a commit of \<repo\>" errors too.

  Resolving a public repository makes one `git ls-remote` round-trip instead of two.

- `pnpm install` after moving a dependency between `dependencies`, `devDependencies`, and `optionalDependencies` now updates the lockfile in place instead of re-resolving the whole dependency graph [#13696](https://github.com/pnpm/pnpm/issues/13696).

- `--ignore-pnpmfile` is accepted again, on every command pnpm takes it on: `install`, `add`, `update`, `dedupe`, `fetch`, `unlink`, `deploy`, `ci`, and `install-test` [#13808](https://github.com/pnpm/pnpm/issues/13808). The flag skips every pnpmfile hook the command would otherwise run: neither the workspace `.pnpmfile.cjs` nor the pnpmfiles of config dependencies are loaded, so no `readPackage`, `updateConfig`, `afterAllResolved`, custom resolver, or custom fetcher runs.

- `syncInjectedDepsAfterScripts` now removes the bin link of a bin the script dropped. Previously only new bins were linked, so a build step that stopped declaring one left its shim behind, pointing at a command that was no longer there.

- Fixed dependency resolution letting the order in which concurrent resolutions finished decide the outcome. When one package was reached from several places, whichever occurrence got there first decided the versions its dependencies were recorded at, so repeated installs of the same project could produce different `pnpm-lock.yaml` files.

- Widening a dependency's range no longer leaves the project on an older version. The lockfile update now points the project at the highest version of that dependency already in the lockfile that satisfies the new range — matching what a full resolution records — instead of keeping the locked version whenever it happened to satisfy, which could leave a duplicate behind. A range change that only an already-locked version satisfies is now also handled without re-resolving [#13778](https://github.com/pnpm/pnpm/issues/13778).

- The lockfile's `time:` section is no longer dropped when `pnpm-lock.yaml` is rewritten. `resolutionMode: time-based` records each direct dependency's publish date there and now reads it back as the fallback for a package whose registry metadata carries no publish date, so a later resolution derives the same cutoff instead of picking different subdependency versions [#13776](https://github.com/pnpm/pnpm/issues/13776).

- `resolutionMode` is no longer ignored when `minimumReleaseAge` is in effect. `lowest-direct` and `time-based` pick the lowest satisfying version of a direct dependency again; previously any active release-age cutoff — including the built-in default — silently forced the highest, so `resolutionMode` only worked when `minimumReleaseAge: 0` was set explicitly [#13752](https://github.com/pnpm/pnpm/issues/13752).

- Adding a package to a workspace no longer forces a full re-resolution when every dependency it declares is already locked for a sibling. The lockfile update writes the new project's importer entry from the versions the lockfile already holds; a dependency no locked version satisfies still reaches the resolver [#13696](https://github.com/pnpm/pnpm/issues/13696).

- Changing a `pnpm.overrides` entry to a version range now updates the lockfile in place when a version the lockfile already holds satisfies the range, instead of re-resolving the whole dependency graph. Only exact versions were handled before [#13696](https://github.com/pnpm/pnpm/issues/13696).

- `pnpm add <pkg>@<version>` and `pnpm update <pkg>@<version>` now move a catalog entry's resolution to the requested version. Previously, when the catalog entry was a range that covered the requested version but resolved to a different one, the request was dropped silently: nothing was installed, nothing was written, and no error was raised.

- Changing a parent-scoped `pnpm.overrides` entry (`"parent>child": "2.0.0"`) now updates the lockfile in place instead of re-resolving the whole dependency graph. Only the named parent's dependency moves; every other package keeps the version it had [#13795](https://github.com/pnpm/pnpm/issues/13795).

- Reduced peak memory usage while resolving peer dependencies. Workspaces with large, deeply peer-dependent dependency graphs could need gigabytes to install; the same install now needs meaningfully less.

- Removing a dependency, or moving one to another already-locked version, no longer re-resolves the whole dependency graph just because some package resolves a peer with the same name. The lockfile update now compares the peer suffixes against the exact `name@version` the removal severed, so a suffix that names a different — still present — version of that dependency is left alone [#13781](https://github.com/pnpm/pnpm/issues/13781).

- `pnpm install` no longer re-resolves dependencies inside a subtree the lockfile pinned when another dependency reaches the same package. Those packages kept their locked versions in `node_modules` while `pnpm-lock.yaml` recorded newer ones, so an install could quietly move a transitive dependency — including across a major version — without anything asking it to.

- A `.pnpmfile.cjs` `readPackage` hook that rewrites one of a project's *own* dependency specifiers is now honored: rewriting `"is-positive": "^1.0.0"` to `1.0.0` installs 1.0.0 and records `specifier: 1.0.0` for the importer. Previously the hook was applied only to the manifests of resolved dependencies, so a project's own specifier resolved against the raw range from `package.json` [#13769](https://github.com/pnpm/pnpm/issues/13769).

- `pnpm prune` now prints the `Scope: all N workspace projects` line when run inside a workspace, as it prunes every project of the workspace.

- Removing a package from a workspace now drops its importer entry from `pnpm-lock.yaml`, along with the dependencies only it needed. Previously the entry survived every later install, which kept those dependencies reachable and made the lockfile diverge from the one the TypeScript CLI writes [#13783](https://github.com/pnpm/pnpm/issues/13783).

- `pnpm remove` no longer re-resolves the dependency graph. The removed dependency's entries are dropped from `pnpm-lock.yaml` and anything they made unreachable is pruned, without registry access. The install still falls back to a full resolution when a surviving package resolves a peer dependency through the removed one.

- An install sharing a global virtual store no longer removes an incomplete package directory that another importer is still writing, which could fail with `failed to remove existing directory ... prior to swap: Directory not empty`. Such a directory is now repaired in place, and a package file left damaged by an interrupted install is restored instead of being kept.

- `pnpm sbom` no longer emits components for optional platform-specific dependencies that cannot be installed on the current platform (for example, the native `@rolldown/binding-*` variants for other operating systems). Such packages are present in the lockfile but are never downloaded, so their license (and other metadata) could not be resolved and they appeared in the SBOM without one. `pnpm sbom --lockfile-only` still describes the whole lockfile graph, which is platform-independent by design.

- Kept unselected workspace link targets shallow during filtered isolated installs.

- Reduced peak memory usage while resolving peer dependencies further: each occurrence in the dependency tree now shares its package id with the edge it came from instead of owning a copy of it.

- An `ssh://` git dependency pointing at a bracketed IPv6 host, such as `ssh://[::1]/repo.git`, is resolved now. Its colons were read as an SCP-style path separator, which turned the address into `[:/1]` and left the specifier unresolvable. Applies to both the TypeScript CLI and pacquet.

  In the TypeScript CLI, an `ssh://` git dependency written without user info — `ssh://git.example.com/team/repo.git`, `git+ssh://git.example.com:2222/team/repo.git` — no longer fails with `TypeError: Cannot read properties of undefined (reading 'includes')`. Only the `user@host` form worked before.

- Commands in a project that pins a pnpm version no longer read the whole `pnpm-lock.yaml` to get at the leading env document. Reading stops at the end of that document, so the cost no longer grows with the rest of the lockfile: reading the env document out of an 8 MB lockfile takes ~15µs instead of ~390µs.

- An install that drops the last dependent of a patched package no longer updates the lockfile in place and succeeds silently. Removing a dependency, widening `ignoredOptionalDependencies`, or adding a removal override could each prune the package while the patch stayed configured; such an install now falls back to a full resolution, which reports the unused patch with `ERR_PNPM_UNUSED_PATCH`. Under `allowUnusedPatches`, where the lockfile update is kept, the same install now warns that the patch went unused instead of saying nothing [#13827](https://github.com/pnpm/pnpm/issues/13827).

## 12.0.0-rc.3

### Patch Changes

- Command shims on POSIX again `exec` a target that has no interpreter — a runtime binary such as the managed Node.js, or any bin without a shebang — instead of waiting on it. A shim that waited reported a target killed by a signal as exit code `128+N` (for example `137` for `SIGKILL`), so callers that distinguish a signal death from an exit code, such as CI runners and process supervisors, saw the wrong outcome.

## 12.0.0-rc.2

### Minor Changes

- Globally installed bins can now follow the project you run them in. The new `globalShims` setting is a record of package names to policies that selects which globally installed packages get project-aware shims; it defaults to `{ node: true, deno: true, bun: true }` and merges key-wise, so `globalShims: { bun: false }` switches one default off and `globalShims: { typescript: true }` adds another package. With the default, a project that pins Node.js through `devEngines.runtime` or `engines.runtime` gets the pinned stable release — authenticated against the Node.js release-team signatures — downloaded on first use and run whenever you type `node` inside the project, with no shell hooks. Candidates that are not signature-verified (Deno, Bun, Node.js prereleases, and ordinary package bins you enable) ask "Do you trust this project?" once per candidate and remember the answer machine-locally; the record values name the policy per package: `"auto"` (or its shorthand `true`) defers to artifact authentication, `"always"` switches without ever asking (useful in CI), and `"prompt"` always asks, even for authenticated candidates. Set `globalShims: false` to disable the feature, or `PNPM_SHIM_BYPASS=1` to bypass it for one invocation. On Windows, programs can keep spawning the global `node.exe` directly, without a shell.

### Patch Changes

- `pnpm dlx` and `pnpm create` no longer fail with "Failed to read patch file" in a project that has `patchedDependencies`. As in pnpm, the package dlx runs is installed unpatched.

- Reduced the warm startup overhead of project-aware managed runtime shims.

- `ng build` and `nuxt build` now work under the global virtual store: pnpm's built-in compatibility extensions add the `tslib` dependency that `@angular/build` uses without declaring and the `unplugin` dependency that `@nuxt/vite-builder` v4 uses without declaring.

- The automatic `packageManager` version switch works again on registries whose tarball URLs point at a different host than the registry itself (load-balanced feed proxies, Artifactory-style mirrors). Package-manager entries are now always recorded with integrity-only resolutions — the download URL is derived from the trusted bootstrap registry instead — and entries persisted in an invalid shape by an earlier pnpm are discarded and re-resolved instead of failing every command [#13619](https://github.com/pnpm/pnpm/issues/13619).

- Registries that serve no npm signature metadata (private mirrors and feed proxies commonly strip `dist.signatures`) no longer break the automatic `packageManager` version switch and `pnpm self-update` [#13147](https://github.com/pnpm/pnpm/issues/13147). When the configured registry cannot provide a verifiable signature, pnpm now fetches the signature from `registry.npmjs.org` and verifies it against the same embedded npm keys over the installed integrity — which proves exactly the same thing. If no signature can be obtained from either source (for example, both are unreachable, or the registry publishes only a `shasum`), pnpm proceeds with a warning instead of failing, but only when the packages resolve through a registry configured in the user's own (non-project) configuration; the download stays pinned by the lockfile integrity, and a signature that exists but does not validate still fails the switch.

- `pnpm setup` no longer makes Node.js print a `MODULE_TYPELESS_PACKAGE_JSON` warning about `dist/worker.js` on every command. The `package.json` it writes next to a standalone executable now declares `"type": "module"`.

## 12.0.0-rc.1

### Major Changes

- Git dependencies on known hosts (GitHub, GitLab, Bitbucket) are now treated as identities rather than transport choices. Every representation of the same repository — `github:owner/repo`, `owner/repo`, `git+https://…`, `git+ssh://git@…` — resolves through the host's canonical HTTPS URL, and the lockfile never records an SSH URL for them. Repositories whose archive endpoint is anonymously reachable resolve to the host's archive (fast tarball download); all others resolve to a `git` clone of the canonical HTTPS URL, which every machine with access to the repository can fetch.

  To reach a private hosted repository over SSH, configure the machine (not the project) with git's own URL rewriting, for example:

  ```sh
  git config --global url."git@github.com:".insteadOf https://github.com/
  ```

  pnpm shells out to `git`, so the rewrite applies to all of pnpm's git operations automatically. URLs of unknown hosts (self-hosted servers) are unaffected and keep their exact URL, including SSH. URLs with embedded credentials are also kept verbatim and never resolve to a host archive.

  This removes the network probing that previously decided between HTTPS and SSH at resolution time, which could record a transport that only worked on the machine that happened to run the resolution (e.g. an SSH URL that broke CI runners without SSH keys).

### Minor Changes

- Added interactive group selection to `pnpm update --global --interactive`.

- `pnpm root -g` and `pnpm bin -g` now print warnings to stderr instead of stdout, so their stdout stays a clean, machine-readable path. Previously, running either command with `--global` in a project that pins a package manager (e.g. via the `packageManager` field) printed a warning like `[WARN] Using --global skips the package manager check for this project` ahead of the path, breaking programs that capture the output as a path [#13672](https://github.com/pnpm/pnpm/issues/13672).

  In pnpm 12, `pnpm root -g` and `pnpm prefix -g` are now supported (they previously failed with `ERR_PNPM_CLI_ROOT_GLOBAL_UNSUPPORTED` / `ERR_PNPM_CLI_PREFIX_GLOBAL_UNSUPPORTED`), and the reporter output of `dlx`, `create`, `config`, `sbom`, `with`, `store`, `prefix`, `root`, and `bin` goes to stderr, matching pnpm 11.

### Patch Changes

- Fixed `minimumReleaseAge` fallback for custom dist-tags so the selected version does not exceed the registry’s original tag target.

- Removing a dependency from `package.json` and reinstalling no longer re-resolves the dependency graph. The importer's entry is dropped from `pnpm-lock.yaml`, anything it made unreachable is pruned, and a catalog entry that loses its last referent is removed — all without registry access. Installs still fall back to a full resolution when a package that stays resolves a peer dependency through the removed one, since that would change the surviving package's entry rather than only prune.

- Dependencies declared with an empty version range (`"adler-32": ""`) install again instead of failing with `ERR_PNPM_NO_MATCHING_VERSION` [#13673](https://github.com/pnpm/pnpm/issues/13673). An omitted range means "any version", as it does in npm and pnpm v11, so packages that publish one — such as `js-xlsx`, `codepage`, and `ssf` — no longer need an `overrides` entry to install.

- Changing a catalog entry to a different exact version no longer re-resolves the dependency graph. The package is replaced in `pnpm-lock.yaml` directly, reusing the same check the `pnpm.overrides` fast path applies: every locked dependency of the package must still satisfy the new version's manifest. Installs fall back to a full resolution when anything other than the catalog reaches the package — an importer that depends on it directly, or another package that depends on it — since the graph would then need both versions.

- Fixed installs under `enableGlobalVirtualStore` failing with `failed to remove existing directory ... prior to swap: Directory not empty` (or `No such file or directory`) when peer variants of an injected `file:` dependency hash to the same slot. The link pass now materializes each unique slot directory once instead of racing one force-mode import per peer variant against the same path.

- The held-back-update warning printed by `pnpm update` no longer fires when `minimumReleaseAge` is the actual reason a newer version was not picked. The warning's baseline now applies the same maturity cutoff as the pick itself, so it no longer wrongly attributes the hold-back to "your manifests and already installed dependencies" or recommends an override that would defeat the age gate. See pnpm/pnpm#13071.

- Changing `autoInstallPeers`, `dedupePeers`, `peersSuffixMaxLength`, `excludeLinksFromLockfile`, or `injectWorkspacePackages` no longer re-resolves the dependency graph when the lockfile proves the setting cannot affect it: no package or project declares a peer dependency for the peer settings, and no project depends on a directory or on another workspace project for the link and injection settings. The new setting is recorded in `pnpm-lock.yaml` and the install proceeds from the existing resolution. Every other case still falls back to a full resolution.

- Adding, editing, or removing an entry in `patchedDependencies` no longer re-resolves the dependency graph. Resolution never reads a patch — it only records the patch file's hash against the package it matches — so the install now rewrites the affected entries in `pnpm-lock.yaml` and materializes the patched package from the store instead. Installs still fall back to a full resolution when the patched package is reachable as a peer dependency, and when the new configuration would leave a patch unused while `allowUnusedPatches` is off, so `ERR_PNPM_UNUSED_PATCH` is still reported.

- `pnpm install` again records immature versions picked under `minimumReleaseAge` (when `minimumReleaseAgeStrict` is off) in `minimumReleaseAgeExclude` in `pnpm-workspace.yaml`, so a later frozen install of the same lockfile passes verification [#13687](https://github.com/pnpm/pnpm/issues/13687).

- Reduced peak install memory: cached registry metadata is now read on demand from the on-disk metadata cache instead of being held in memory for the whole resolution. Resolving a large peer-heavy graph (`@teambit/bit`) peaks at about 1.3 GB instead of 3.2 GB, and a full cold install of it stays under 2 GB [#13681](https://github.com/pnpm/pnpm/issues/13681).

- Lockfile verification now honors offline mode by using cached registry metadata instead of reaching the registry. When the required metadata is not available locally, verification reports the same `ERR_PNPM_NO_OFFLINE_META` condition used by offline resolution.

- POSIX shell shims now follow symbolic links before computing `basedir`, preventing execution failures when a shim is invoked via an external symlink on `PATH` [#13405](https://github.com/pnpm/pnpm/issues/13405).

- Speed up installs after adding `ignoredOptionalDependencies` patterns by removing newly ignored optional dependencies and pruning packages that are no longer reachable without resolving the dependency graph again.

- `pnpm self-update` no longer fails with `the installed pnpm wrapper is missing` when the global packages directory carries a `pnpm-workspace.yaml` of global settings (written there when a global install persists an `allowBuilds` decision). The engine install stays anchored to its own install directory instead of walking up and adopting that file as its workspace root. The `pnpm dlx` cache install gets the same anchoring, so a stray `pnpm-workspace.yaml` above the cache directory can no longer break it [#13697](https://github.com/pnpm/pnpm/issues/13697).

- Reduced peak memory usage and allocation churn during peer dependency resolution on workspaces with many peer-dependency issue occurrences [#13681](https://github.com/pnpm/pnpm/issues/13681).

- `pnpm update` without saving no longer records a version that the manifest's range excludes. The kept range stays authoritative: a requested version outside it is skipped with a warning, and a requested range, a dist tag, or `--latest` resolves within it instead of past it. Previously each of these could write a lockfile entry that contradicted its own specifier, which the next `pnpm install --frozen-lockfile` rejected with `ERR_PNPM_OUTDATED_LOCKFILE` [#12764](https://github.com/pnpm/pnpm/issues/12764).

- `pnpm version -r --json` now outputs `[]` instead of human-readable text when no pending changes exist [`pnpm/pnpm#13217`](https://github.com/pnpm/pnpm/issues/13217).

- On Windows, installation no longer fails with "A required privilege is not held by the client. (os error 1314)" when symlink creation requires elevation (e.g. Developer Mode is off) — pnpm now falls back to NTFS junctions in that case. Additionally, `pnpm clean` and `pnpm deploy --force` no longer fail with "Access is denied. (os error 5)" when removing the package links inside `node_modules` [#13694](https://github.com/pnpm/pnpm/issues/13694).

## 12.0.0-rc.0

### Minor Changes

- Running `pnpm setup`, `pnpm self-update`, or a command that modifies the global installation (such as `pnpm add --global`) through `sudo` now fails with `ERR_PNPM_SUDO_NOT_SUPPORTED` instead of silently operating on the root user's home directory. pnpm keeps global packages and configuration in the invoking user's home directory, so these commands never need root permissions. Read-only global commands (such as `pnpm bin --global`) still work under sudo.

### Patch Changes

- Archive entries whose paths use `\` as a separator are now read the same way pnpm reads them. A nested path spelled `bin\tool.js` by Windows publishing tooling resolves to `bin/tool.js`, and a path traversal spelled with backslashes is rejected instead of being stored verbatim.

- Fixed `file:` dependencies not being re-copied when their source directory changed. A `file:` dependency is copied into the store at install time rather than symlinked, so editing the local package's files and running `pnpm install` again left the previous copy in place — the lockfile is unchanged by such an edit, so the install treated the tree as up to date.

- Write blocked-build approval scaffolding to the discovered workspace manifest when using per-project lockfiles.

- Concurrent installs sharing a global virtual store no longer fail with `failed to remove existing directory ... prior to swap: Directory not empty`, and no longer briefly remove a package directory another process is reading.

- Fixed `link:` dependencies under `enableGlobalVirtualStore` so linked children are materialized and slots remain isolated by their resolved link targets.

- A headless install (`--frozen-lockfile`) now creates the command shims for a publicly hoisted workspace package's `bin`, matching what a normal install already did and what pnpm's own headless install does. Previously those shims were missing until the next non-frozen install.

- `pnpm fetch`, and any install run with `virtualStoreOnly`, no longer writes a `.pnp.cjs` loader under `nodeLinker: pnp`. These installs populate the virtual store without linking the project, so the loader would have claimed the project resolves out of a store it was never linked into. The importer links and `node_modules/.package-map.json` were already skipped; the PnP loader now follows the same rule.

- Prevent pnpm from removing project files when `modulesDir` resolves to the project root.

- Fixed `pnpm install` ignoring a `pnpm-lock.yaml` that carries a leading env lockfile document when the file has CRLF line endings or a UTF-8 byte order mark, as a `core.autocrlf` checkout on Windows produces. The lockfile was reported as broken with `multiple YAML documents detected` and every dependency was re-resolved from the registry [#13606](https://github.com/pnpm/pnpm/issues/13606).

- When no directory above the project accepts a hard link — inside an AI agent sandbox that only grants write access to the project, or a container with just the project mounted writable — the default store is now created at `<project>/node_modules/.pnpm-store` instead of in the pnpm home directory. In those environments the home store is either read-only or on another volume, which forces every package to be copied instead of hard linked [#13525](https://github.com/pnpm/pnpm/issues/13525).

- A stray non-directory entry in `node_modules` no longer fails an install. Files placed next to the installed dependencies are skipped rather than reported as an unreadable manifest.

## 12.0.0-beta.4

### Minor Changes

- **Security fix.** Affects projects using `namedRegistries` on pnpm 11.1.0–11.19.x. It is **semi-breaking** for those projects — see "If you use named registries" below.

  The lockfile recorded no marker for which registry a package came from. Packages were keyed by `name@version` alone, and entry lookup went through `refToRelative(ref, name)`, so a dependency you declared against one registry could be satisfied by an entry that was actually resolved from another. When two registries served the same name and version, both collapsed onto a single `packages:` entry and whichever resolved first decided the tarball every consumer got.

  That is a package-substitution risk: a package you expect from your private registry could be installed from a different registry that publishes the same name and version, and the lockfile recorded nothing that would let you tell.

  Packages resolved from a named registry are now recorded under registry-qualified keys (`<name>@<registryName>:<version>`, e.g. `foo@work:1.0.0`), so each registry gets its own entry and the lockfile pins which one a dependency came from.

  The lockfile format version is unchanged. Registry-qualified keys appear only for packages resolved from a named registry, so a project that does not use `namedRegistries` sees no difference, and older pnpm versions keep reading the file.

  ### If you use named registries

  Your next non-frozen install re-keys those entries, which shows up as a lockfile diff. Commit it — that diff is the fix being applied. Review it: an entry that moves to a registry you did not expect is worth investigating.

  Everyone working on the project should be on this version or newer before you do. An older pnpm reads the re-keyed lockfile fine — frozen installs are unaffected — but it does not produce registry-qualified keys itself, so any install that updates the lockfile writes those entries back to the old shape, and the next install on a current pnpm re-qualifies them. The result is a lockfile that flips back and forth, and while it is in the old shape the project is exposed again. Because the lockfile format version is deliberately unchanged, pnpm cannot detect this and warn you about it.

  There is no setting to keep the old behavior: the old shape is the vulnerability.

  Tarball URLs that follow the standard registry layout are no longer written to the lockfile for named-registry packages; they are recomputed from the `namedRegistries` setting on demand.

  To use named registries, map your aliases in `pnpm-workspace.yaml`:

  ```yaml
  namedRegistries:
    work: https://npm.enterprise.example.com/
  ```

  ### New built-in `npmjs:` alias

  `npmjs:` now resolves to `https://registry.npmjs.org/` with no configuration, alongside the existing `gh:` alias for GitHub Packages. It pins a dependency to the public registry even when `registry` points elsewhere, such as an internal proxy:

  ```json
  { "dependencies": { "left-pad": "npmjs:^1.3.0" } }
  ```

  `npm:` cannot do this — it is the alias protocol (`npm:<name>@<range>`) and resolves through whatever `registry` points at.

  **If you mirror or proxy npmjs, point the alias at your mirror:**

  ```yaml
  namedRegistries:
    npmjs: https://npm.internal.example.com/
  ```

  Built-in registry URLs are also the prefixes a lockfile's recorded tarball URL is matched against when pnpm verifies a package. Without the override, an entry whose tarball URL is on `registry.npmjs.org` is verified against the public registry rather than your mirror. This only affects lockfiles that record such URLs — a canonical URL for your configured registry is omitted from the lockfile and unaffected — and only when a tarball-URL, `minimumReleaseAge`, or `trustPolicy` check runs. Overriding the alias is the same escape hatch GHES users already have for `gh`.

  Every alias the lockfile references must stay in `namedRegistries`: reading an entry whose alias is gone fails with `ERR_PNPM_MISSING_NAMED_REGISTRY` rather than silently falling back to the default registry, since that would fetch a different package. Renaming an alias re-resolves the packages that used it.

  Named registry aliases that shadow a reserved dependency specifier prefix (`file`, `link`, `workspace`, `runtime`, `npm`, `jsr`, ...) are now rejected with `ERR_PNPM_RESERVED_NAMED_REGISTRY_NAME` instead of being silently shadowed by the corresponding resolver.

  `pnpm licenses` and `pnpm sbom` now keep the two artifacts apart as well: license records carry the registry alias, and SBOM components carry the purl `repository_url` qualifier.

### Patch Changes

- Installing a workspace whose projects auto-install peer dependencies is substantially faster. Each round of the peer-hoist loop no longer scans the whole workspace once per project, so the cost of resolution grows with the workspace instead of with its square.

- Installing a dependency chain whose packages carry peer dependencies no longer expands exponentially with the depth of the chain. A single project with a single such dependency could exhaust memory before finishing; it now resolves in tens of megabytes.

- Fixed non-deterministic resolution on multi-project workspaces: two consecutive installs of the same inputs could bind peer-suffixed packages to different (still valid) providers, rewriting `pnpm-lock.yaml` on every install [#13567](https://github.com/pnpm/pnpm/issues/13567).

- Installing a workspace now produces the same `pnpm-lock.yaml` every time. Two installs of the same workspace could previously bind a peer dependency to a different — still valid — version, which changed the lockfile without anything in the project changing.

- An empty `http-proxy`, `https-proxy`, `proxy`, or `no-proxy` value — from the `.npmrc`, `pnpm-workspace.yaml`, the CLI, or the `HTTP_PROXY` / `HTTPS_PROXY` / `PROXY` / `NO_PROXY` environment variables — no longer fails the install with `ERR_PNPM_INVALID_PROXY`. Empty settings read as unset, so a shell exporting `HTTP_PROXY=` disables the proxy, and an empty `proxy=` in the `.npmrc` no longer suppresses `HTTPS_PROXY` [#13533](https://github.com/pnpm/pnpm/issues/13533).

  `proxy=false` in the `.npmrc` or `proxy: false` in `pnpm-workspace.yaml` now turns proxying off instead of being read as a proxy host named `false`. `false` and `null` on `https-proxy` / `http-proxy` / `no-proxy` read as unset, and on the command line they are ordinary host names, since a flag carries its value verbatim.

- The env lockfile no longer pins `@pnpm/exe` alongside `pnpm` when the wanted pnpm version is 12 or newer. From v12 the unscoped `pnpm` package is itself the native executable, so `@pnpm/exe` is not published for it and resolving it would fail. The engine identity check now verifies the native binary through whichever package ships it.

- Resolution on large peer-heavy workspaces got faster: a Bit workspace with 114 projects and ~21,000 lockfile entries resolves in ~13.4s instead of ~16.0s. The resolved dependency graph is unchanged.

- Fixed nondeterministic peer bindings in large multi-project workspaces.

- Resolving a workspace whose dependency chains are deep is faster: deciding which missing peer dependencies another project's resolution already covers now answers once per shared chain segment instead of once per report.

- Peer resolution on large workspaces got faster: each hoist round now refreshes its view of the dependency graph from what the round changed instead of re-reading every resolved package. The resolved dependency graph is unchanged.

- `pnpm install` no longer crashes on a machine whose system certificate store is empty or absent — for example a minimal container or build sandbox that ships no CA certificates [#13588](https://github.com/pnpm/pnpm/issues/13588). Such a system now falls back to the Mozilla root certificates bundled into the binary, the same set Node.js ships, so both offline and online installs work again. Certificates from the system store, `NODE_EXTRA_CA_CERTS`, and the `.npmrc` `ca` / `cafile` settings keep taking precedence whenever any of them is available.

- Fixed the order in which pnpm matches a lockfile's recorded tarball URL against known registry URLs. Two registry URLs of equal length were previously ordered arbitrarily, so which one a tarball URL matched could differ between runs.

- `pnpm login` / `pnpm adduser` now read the `scope` setting from `pnpm-workspace.yaml`, the global `config.yaml`, and the `PNPM_CONFIG_SCOPE` environment variable, not only from the `--scope` command-line flag. When `scope` is configured, the granted token is keyed to that scope and the scope-to-registry mapping is recorded. `--scope` still takes precedence when both are set. Note that `scope` in an `.npmrc` is not read — pnpm keeps only auth and registry keys from that file.

- Resolution spends less time in its final peer pass: the package-name cycle graph it consults is now derived once per package instead of once per occurrence of that package.

- npm's `--prefix` is accepted as a spelling of `--dir`, and `--store` as a spelling of `--store-dir`, so `pnpm --prefix ../ run test` no longer fails with "unexpected argument '--prefix' found" [#13583](https://github.com/pnpm/pnpm/issues/13583).

- pnpm now ships `node-gyp` again, so packages whose install scripts shell out to it build out of the box. Previously they failed with `spawn node-gyp ENOENT` unless a `node-gyp` was already on `PATH` — affecting `node-gyp-build` with no matching prebuild, `node-pre-gyp`, a plain `"install": "node-gyp rebuild"`, and any package shipping a `binding.gyp` without an install script. As in pnpm 11, the whole `node-gyp` dependency tree is resolved from pnpm's own lockfile when pnpm is released, so it is frozen per release rather than resolved on your machine, and `npm_config_node_gyp`, a workspace `node-gyp`, and a package's own `node-gyp` dependency all still take precedence.

## 12.0.0-beta.3

### Patch Changes

- With `excludeLinksFromLockfile` enabled, a `link:` dependency pointing inside the workspace is no longer treated as an external link when it resolves a peer dependency, so the peer suffixes it produces stay identical to an install with the setting off. Injected (`file:`) workspace dependencies are no longer affected by the setting either [#13556](https://github.com/pnpm/pnpm/issues/13556).

- A registry dependency is now always recorded in `pnpm-lock.yaml` with an integrity hash, including under `--lockfile-only`. Packages from a registry that publishes no subresource-integrity metadata — `https://node-registry.bit.cloud/`, for one — were recorded without one, so the next `pnpm install --frozen-lockfile` failed with `ERR_PNPM_MISSING_TARBALL_INTEGRITY` [#13547](https://github.com/pnpm/pnpm/issues/13547).

- Dependency resolution is faster: package metadata is now filtered once per packument instead of once per dependency edge when `minimumReleaseAge` is active, and parsed semver versions and ranges are reused instead of re-parsed on every comparison.

- Reduced memory use when resolving peer-heavy dependency graphs and prevented nested hoisted graphs from expanding into excessive dependency paths.

## 12.0.0-beta.2

### Patch Changes

- `pnpm install` no longer fails when `pnpm-lock.yaml` exists but cannot be parsed. Matching the TypeScript CLI, the install now prints an "Ignoring broken lockfile" warning, resolves dependencies from the manifests, and rewrites the lockfile. `--frozen-lockfile` still fails on a broken lockfile.

- Fresh installs no longer download the tarballs of platform-specific optional dependencies that don't match the current platform.

## 12.0.0-beta.1

### Minor Changes

- Made peer resolution significantly faster in large multi-importer workspaces (a 114-importer workspace's resolution dropped from ~77s to ~36s): importers whose hoist rounds converged no longer re-walk their dependency forest every round, later rounds walk only newly added direct dependencies, ownership handovers with an unchanged peer context no longer invalidate shared walk caches, and the resolver's internal hash maps use a faster hash. Peer dependencies provided by multiple candidate versions may resolve to a different (still range-valid) provider than before, which can shift some peer-variant suffixes in `pnpm-lock.yaml` once.

- `pnpm login` no longer requires an interactive terminal when the registry supports web-based login: without a TTY it prints the authentication URL (skipping the QR code and the "Press ENTER to open the URL in your browser" prompt) and polls the registry until the browser approval completes. Only the classic username/password login still fails with `ERR_PNPM_LOGIN_NON_INTERACTIVE` in a non-interactive terminal.

- Added `projects[].dependencyManifest` to the `@pnpm/napi` install options: the manifest a workspace project exposes when it is resolved as a dependency of another importer (an injected instance). Hosts that pre-transform their importer manifests no longer need a `readPackage` hook to substitute the raw manifest, and per-manifest deletions are expressed through the existing `overrides` removal syntax (`"pkg": "-"`), so resolution can run without any JS round trips.

- The `save-prefix` setting now accepts `=`: newly added dependencies are saved with an explicit `=` operator (`=1.2.3`) instead of the setting being silently treated as the default `^`.

### Patch Changes

- `allowBuilds` entries can now approve git-hosted packages that pnpm downloads as a tarball, such as `github:` dependencies (which are fetched from `codeload.github.com` rather than cloned), by their repository URL without the resolved commit hash. This matches the hashless `git+` matching already supported for cloned git dependencies. For example:

  ```yaml
  allowBuilds:
    "foo@git+https://github.com/org/foo.git": true
  ```

  This approves the package whether pnpm clones it or downloads a tarball, so the entry no longer has to be updated every time the pinned commit changes. GitLab and Bitbucket tarball downloads are matched the same way. Approving or denying a specific resolved commit by its full tarball dep path continues to work.

- Fixed a severe slowdown resolving large workspaces against registries whose abbreviated metadata lacks per-version `time` fields (such as `node-registry.bit.cloud`) while `minimumReleaseAge` is active. The resolver upgraded the abbreviated packument to full metadata once per *dependency edge* instead of once per package — re-requesting the same packument from the registry hundreds of times in a single install — and a `304 Not Modified` answer was never remembered, so the round trip repeated forever. The upgrade outcome is now cached for the rest of the install. On a 345-project workspace this cut a full resolution from 105 s to 36 s.

  Also stopped the resolver from deep-copying every workspace project manifest on each internal resolve-options clone (the workspace-packages map is now shared by reference).

- Aligned deprecated package warnings with pnpm by reporting each package only on its first resolution and shortening direct dependency warnings during recursive installs.

- `pnpm outdated --include-github-actions` no longer blocks on an interactive git credential prompt when a workflow uses a private action repo.

- `pnpm add` and `pnpm update` now honor the `saveExact` setting; previously only the `--save-exact` flag was respected.

- Fixed parsing very large lockfiles that exceed the YAML parser's default 64 MiB scalar-text budget.

- Fixed parsing large lockfiles that exceed the YAML parser's default structural budget [pnpm/pnpm#12857](https://github.com/pnpm/pnpm/issues/12857).

- Fixed writing lockfiles with dependency paths longer than 1024 characters (long peer suffixes in large workspaces): such keys are now emitted in explicit `? <key>` form, matching the TypeScript CLI. Inline keys of that length are invalid YAML, so pnpm could not re-read the lockfile it had just written and every subsequent install re-resolved from scratch.

- Prevented `minimumReleaseAge` from replacing `latest` with a SemVer-greater version than the registry tag target [#13034](https://github.com/pnpm/pnpm/issues/13034).

- Installs driven through `@pnpm/napi` got three fixes for large workspaces: the `readPackage` hook is now dispatched to JavaScript in batches instead of one event-loop roundtrip per manifest, the `dedupePeers` setting can be passed through the install options (so an existing lockfile generated with it is no longer treated as outdated), and version-pinned dependencies are served from the metadata mirror without queueing behind concurrent registry refreshes of the same package.

- Fixed the `overrides` block of `pnpm-lock.yaml` being rewritten in a random order on every install performed through `@pnpm/napi`. The recorded overrides now keep the order they were declared in, so repeat installs no longer churn the lockfile.

- The `TRACE` environment variable now enables engine tracing for `@pnpm/napi` consumers the same way it does for the pnpm CLI, and an invalid `TRACE` filter no longer aborts the process — it prints a warning and leaves tracing off.

- Fixed peer resolution creating far more peer variants than the TypeScript CLI in multi-importer workspaces: a dependency subtree first resolved under one importer no longer hands the peer providers it resolved to every other importer that shares it. Those importers now bind such peers against their own context (or the workspace root), matching the TypeScript resolver. In a large bit.cloud workspace this cut a from-scratch install from 25,534 to 20,791 lockfile snapshots.

- Fixed empty `bundledDependencies` and `bundleDependencies` arrays causing nondeterministic lockfile changes. See pnpm/pnpm#13123.

- Reduced the peer resolution pass's CPU cost on workspaces with many peer dependencies. The walker cloned its parent peer-context maps at every node — twice per node plus once per child — even when a node contributed nothing to them; the maps are now shared copy-on-write and the derived per-child snapshots are reused unless the context actually changed. On a peer-heavy 331-importer benchmark the full resolution dropped from 3.9 s to 2.8 s.

- The install summary no longer prints `(X is available)` when the registry's `dist-tags.latest` is still held back by the active `minimumReleaseAge` policy. The hint only ever names the actual latest tag, so an immature latest suppresses the hint instead of advertising the version pnpm just refused to install [#11698](https://github.com/pnpm/pnpm/issues/11698).

- `pnpm update` keeps the explicit `=` operator of an exact version pin: a dependency saved as `=3.5.1` now updates to `=3.5.2` instead of the bare `3.5.2`. See pnpm/pnpm#13168.

- Preserve a workspace dependency's `link:` entry when a run does not target it — e.g. `pnpm update <other-pkg>` (with or without `--recursive`), or a plain install after a root/catalog dependency change — with `injectWorkspacePackages`, instead of spuriously rewriting it to a peer-suffixed `file:` protocol. See pnpm/pnpm#10433.

- Fixed resolution of a direct dependency declared in both `dependencies` and `devDependencies`: the `dependencies` specifier now wins, matching the TypeScript CLI. The `devDependencies` range was resolved instead, recording a lockfile importer entry whose version did not satisfy its specifier — which failed the lockfile up-to-date check and forced a full re-resolve on every install.

- Kept the lockfile policy verdict ahead of the frozen-install message when package statistics arrive while verification is still running.

- `overrides` are now applied after the `readPackage` hook during resolution, matching the TypeScript CLI's hook order (`packageExtensions` → `readPackage` hooks → `overrides`). A hook that replaced a manifest — such as a host application substituting a workspace project's raw manifest for its injected instances — previously erased the overrides from that manifest, so the resolved graph ignored them.

- Sped up multi-importer resolution by sharing the run-resolved preferred-versions fold across importers. Every importer replayed the whole workspace's resolved-versions history into a private map each hoist round — O(importers × packages) map inserts and string clones — although the peer-hoist pickers only ever look up a handful of missing-peer names. The fold is now maintained once, workspace-wide, and importers materialize just the buckets they query. Full resolution of a 331-importer benchmark workspace dropped from 886 ms to 424 ms (peer-heavy variant: 2.8 s to 2.4 s).

- Fixed quadratic time and memory use when resolving a large multi-project workspace from scratch. Resolving a workspace with hundreds of projects sharing thousands of packages previously took minutes and several gigabytes of memory; it now completes in seconds.

## 12.0.0-beta.0

### Minor Changes

- The Rust engine now reads four more settings from `pnpm-workspace.yaml` and `PNPM_CONFIG_*`, instead of only accepting them as CLI flags:

  - `frozenLockfile` — `pnpm install` grows a `--no-frozen-lockfile` flag so the setting can be overridden in both directions. As in pnpm, it cannot be set in the global `config.yaml`.
  - `savePrefix` — the range operator `pnpm add` saves, still overridable with `--save-prefix` / `--save-exact`.
  - `savePeer` — `pnpm add` also records the new dependency in `peerDependencies`. `pnpm add --no-save-peer` overrides it back off.
  - `saveCatalogName` — the catalog `pnpm add` saves into.

- The Rust engine now supports the `saveWorkspaceProtocol` setting, so `pnpm add <pkg>@workspace:…` writes back the same specifier pnpm does. Under the default `rolling`, a request like `workspace:^1.2.3` is saved as `workspace:^` — a range with no version in it, so bumping the workspace package never has to touch its dependents' manifests. `saveWorkspaceProtocol: true` saves the workspace package's resolved version instead (`workspace:^2.5.0`), and `false` keeps the `workspace:` form only when it was asked for explicitly. Previously the specifier was written back exactly as typed.

- `pnpm update --workspace` is supported: dependencies that a workspace project publishes are re-pointed at the local copies through the `workspace:` protocol. The `saveWorkspaceProtocol` setting is honored — under its `rolling` default an entry becomes `workspace:*`, `workspace:^`, or `workspace:~` (whichever matches the range it already declared), so a sibling's next release does not invalidate it. Naming a dependency that is not in the workspace fails with `ERR_PNPM_WORKSPACE_PACKAGE_NOT_FOUND`, and combining the flag with `--latest` fails with `ERR_PNPM_BAD_OPTIONS`.

  `pnpm update --depth <number>` is now applied per dependency instead of only distinguishing `0` from higher values: a dependency deeper than the given depth keeps its locked resolution, so `pnpm update --depth 0` updates direct dependencies only.

- `pnpm run "/^build:(backend|frontend)$/"` selects every script whose name matches the pattern, in single-project and recursive runs alike [#13322](https://github.com/pnpm/pnpm/issues/13322). Flags on the selector are rejected with `ERR_PNPM_UNSUPPORTED_SCRIPT_COMMAND_FORMAT`, as pnpm does.

- `pnpm self-update` no longer takes any instruction from the project it is run in:

  - pnpm is fetched through the same trusted registry and auth configuration used when switching pnpm versions, so a project `.npmrc` or `pnpm-workspace.yaml` can no longer redirect the download or attach credentials to it, and the project's default `.pnpmfile.(c|m)js` is no longer loaded. Pnpmfiles from trusted sources (the `pnpmfile` setting, the global pnpmfile, config dependencies) still apply.
  - The `minimumReleaseAge` settings in `pnpm-workspace.yaml` no longer affect `self-update`. They still govern the project's own dependencies; for `self-update` the cooldown now comes from the built-in default, your global config, a `PNPM_CONFIG_*` environment variable, or a command-line flag. This fixes `self-update` failing inside a workspace that raises the cutoff while succeeding everywhere else, and stops a repository from either waiving the cooldown or keeping you on an outdated pnpm by raising it.
  - The same applies to the `trustPolicy` settings and to `ci`: a project can no longer weaken the trust check that guards the pnpm download, nor re-enable the confirmation prompt that a CI run suppresses.

  When `self-update` refuses a version that is younger than the cutoff, an interactive run now offers to update anyway; non-interactive runs still fail. CI never prompts, even on a runner that attaches a TTY.

### Patch Changes

- An aliased dependency of a protocol that resolves under its own package name — `jsr:` and the named registries — is recorded in the lockfile importer again. `"bar-from-jsr": "jsr:@pnpm-e2e/bar@1.0.0"` resolved and installed, but the importer stayed empty, so nothing reading direct dependencies out of the lockfile (`outdated`, `update`, `licenses`, dedupe, frozen-install verification) could see it [#13362](https://github.com/pnpm/pnpm/issues/13362).

- An `allowBuilds` entry with the `set this to true or false` placeholder pnpm scaffolds no longer makes every command in that workspace fail with a config-parse error [#13322](https://github.com/pnpm/pnpm/issues/13322). An undecided entry now leaves the package under the default-deny build policy, as pnpm does.

- Two `pnpm install` resolution fixes that made large workspaces such as [Astro](https://github.com/withastro/astro) produce a different `pnpm-lock.yaml` than pnpm 11 [#13334](https://github.com/pnpm/pnpm/issues/13334):

  - A scoped workspace package referenced through the `file:` protocol (`"@test/pkg": "file:./pkg"`) is recorded as a `link:` again instead of being copied in as a `file:` snapshot.
  - `bundledDependencies` / `bundleDependencies` are no longer resolved as dependencies of their own. npm ships them inside the package's tarball, so installing them again added packages the lockfile should not contain (for example `napi-wasm` under `@parcel/watcher-wasm`).

- Executables that a package ships inside its own tarball (`bundledDependencies`) are linked again into that package's `node_modules/.bin`, under both the isolated and the hoisted node linker. A package that declares `bundleDependencies: true` instead of a list of names is now recorded in `pnpm-lock.yaml` the way pnpm 11 records it, and such a lockfile can be read back.

- Aligned `pnpm licenses list --json` package metadata and license-group ordering with the TypeScript CLI.

- Fixed `pnpm --filter <package> run` to list the selected package's scripts and root workspace scripts when no script name is specified.

- Strip Unicode formatting characters from registry- and manifest-derived terminal output.

- Prevented dependency verification before scripts from rewriting an up-to-date lockfile.

- Concurrent commands in a repository that pins `packageManager` no longer race while installing the pinned pnpm version on a cold cache [#13322](https://github.com/pnpm/pnpm/issues/13322). A task runner spawning several `pnpm run` children at once could previously fail with "failed to remove existing directory … prior to swap", or leave a child looking for a binary another process had just unlinked.

- Config-load warnings, such as the warning about install settings left under the `pnpm` field of `package.json`, are printed to stderr instead of stdout [#13361](https://github.com/pnpm/pnpm/issues/13361).

- `pnpm dedupe --check` now reports what deduplication would change: the importer and package snapshot diff, the `ERR_PNPM_DEDUPE_CHECK_ISSUES` error code, and the warning that points at `pnpm peers check` when the install leaves peer-dependency issues behind. `pnpm peers check` is also accepted again — the subcommand spelling used on pnpm.io and in pnpm's own dedupe output — instead of failing with "unexpected argument 'check' found" [#13321](https://github.com/pnpm/pnpm/issues/13321).

- A deprecated package is reported once rather than once per workspace project that depends on it, and is no longer double-counted in the "deprecated subdependencies found" summary when it is also a direct dependency [#13322](https://github.com/pnpm/pnpm/issues/13322). Ignored build scripts are also listed with their `(patch_hash=…)` suffix, so two copies of a package that differ only by an applied patch are distinguishable.

- `pnpm install --frozen-lockfile` no longer re-imports a varying subset of packages on every repeat install of an unchanged project [#13316](https://github.com/pnpm/pnpm/issues/13316). The global-virtual-store directory of a package that takes part in a dependency cycle was derived from an order that changed from run to run, so those packages landed on a fresh slot each time; it is now derived deterministically and matches the directory pnpm itself computes.

- When the pinned `packageManager` engine install cannot take its lock because the store cannot be written to, pnpm now reports that instead of quietly installing without the lock. A lock another process holds is unchanged — it is still waited for.

- Speed up installs after compatible catalog or direct dependency range changes by retaining the locked version without resolving the dependency graph again.

- Speed up installs after safe override changes by reusing unambiguous compatible dependency resolutions, pruning obsolete dependencies, applying independent replacements and removals together, and handling parent-scoped `"-"` overrides without full lockfile resolution.

- Fixed warm side-effects cache reuse for git dependencies.

- Aligned `pnpm dedupe --check` progress and error output with the TypeScript CLI.

- A frozen install now fails when `autoInstallPeers`, `dedupePeers`, or `excludeLinksFromLockfile` has changed since `pnpm-lock.yaml` was written, instead of installing against a lockfile that no longer matches the settings. The error names the drifted setting, as `pnpm install --frozen-lockfile` has always done.

- A repeat `pnpm install --frozen-lockfile` is a no-op again when the project has a platform-incompatible optional dependency. The skipped package is kept in `node_modules/.pnpm/lock.yaml` (`.modules.yaml` is what records the skip), so the install can once more recognize an unchanged tree instead of re-running every lifecycle and dependency build script [#13312](https://github.com/pnpm/pnpm/issues/13312).

- Reject frozen installs when the current pnpmfile does not match the lockfile's `pnpmfileChecksum`.

- Fixed `pnpm licenses list` to report dependencies from every workspace project, exclude unsupported platform packages, and mark development dependencies.

- Setting both `autoInstallPeers: false` and `dedupePeerDependents: false` now leaves missing peers alone, instead of still installing the ones a version elsewhere in the workspace could satisfy.

- Installing a local `file:` directory dependency with the global virtual store enabled no longer fails with `TypeError: Cannot read properties of undefined (reading 'split')` [#13335](https://github.com/pnpm/pnpm/issues/13335).

  Local directory dependencies — `file:` directories and injected workspace packages — now get a global-virtual-store slot of their own per project. They used to share one slot across every project that depended on a directory of the same name, so a project could end up linked to another project's copy of the dependency.

- A missing required peer is no longer auto-installed as a prerelease that its declared range rejects. A package peer-depending on `^29.0.0 || ^30.0.0` next to a `30.0.0-alpha.6` pulled in elsewhere in the graph now resolves a stable `29.x`/`30.x` from the registry instead of adopting the alpha [#13341](https://github.com/pnpm/pnpm/issues/13341).

- A lockfile entry for a git-hosted archive that records no `integrity` installs again instead of failing with `ERR_PNPM_MISSING_TARBALL_INTEGRITY`. Older pnpm versions wrote that shape for dependencies like `"ci-info": "watson/ci-info#f43f6a1c…"`, so any committed lockfile still carrying one could not be installed [#13308](https://github.com/pnpm/pnpm/issues/13308). The archive URL pins a full commit SHA, and pnpm fetches it without an integrity check.

  Every other remote tarball still has to carry an `integrity`, and the refusal now points at the repair: `pnpm clean --lockfile` followed by `pnpm install`.

  Error output no longer repeats the same message once per level of the internal error chain.

- The `Workspace` column of `pnpm update --interactive` now falls back to the project's path when its `name` is only whitespace, as it already did for a missing or empty one — all three render an equally blank label otherwise.

- `pnpm update --interactive` now groups the dependencies it offers by dependency type — `dependencies`, `devDependencies`, `optionalDependencies`, `peerDependencies`, and GitHub Actions each get their own heading — and lays each group out as a column-aligned table with a `Package`/`Current`/`Target`/`URL` header, instead of one flat list.

- `pnpm update --interactive` now measures its table in terminal columns rather than in characters. A package name, workspace name, or version containing wide characters (CJK, most emoji) no longer knocks its row's columns out of line with the rest of the group, and a wide character in a version no longer aborts the command with `Subject parameter value width cannot be greater than the container width` [#13357](https://github.com/pnpm/pnpm/issues/13357).

- `pnpm update --interactive` run inside a workspace now shows a `Workspace` column naming the project each outdated dependency was found in, so the same package outdated in several projects can be told apart.

- Write single-value `libc` package metadata in the same scalar form as pnpm.

- Fixed `pnpm install` silently skipping a local `file:*.tgz` dependency: the package is now extracted into the virtual store, recorded under `packages:` and `snapshots:`, and linked into `node_modules` [#13379](https://github.com/pnpm/pnpm/issues/13379).

- A frozen install whose recorded settings no longer match the configuration — `overrides`, `catalogs`, `patchedDependencies`, and the rest — now fails with `ERR_PNPM_LOCKFILE_CONFIG_MISMATCH` naming the one setting that changed, instead of `ERR_PNPM_OUTDATED_LOCKFILE` with the whole map dumped [#13322](https://github.com/pnpm/pnpm/issues/13322).

- Fixed `pnpm install` dropping a package that ships no `package.json` of its own from the lockfile. Such a package is now named after its alias and recorded at version `0.0.0` under `packages:` and `snapshots:`, and its extraction gets the placeholder `package.json` pnpm writes [#13410](https://github.com/pnpm/pnpm/issues/13410).

- Resolve optional peers from versions provided by local workspace packages, omit empty deprecation messages from generated lockfiles, and preserve valid lockfile pins in `pnpm dedupe --check`.

- Aligned license reports, `dedupe --check` progress and spacing, and dependency-verification output with pnpm.

- A `file:` dependency declared by a package that was itself installed from a local directory is now resolved relative to that package's directory, not to the importer's [#13323](https://github.com/pnpm/pnpm/issues/13323). Installing a project whose local dependency depends on a sibling directory (`file:../child`) no longer fails with `Could not install from "…" as it does not exist`, and the snapshot entry for such a dependency is now written as `file:<path>` instead of `<name>@file:<path>`, matching the lockfile pnpm writes.

- `npm_config_user_agent` now carries the configured user agent (`pnpm/<version> …`) in install lifecycle scripts, `pnpm run`, `pnpm exec`, and `pnpm dlx` [#13322](https://github.com/pnpm/pnpm/issues/13322). It was previously unset for install scripts and the bare string `pnpm` elsewhere, which made `preinstall` guards that check for pnpm reject the install.

- An auto-installed *optional* peer is no longer hoisted at a version the workspace root's own dependency on that package excludes. `resolvePeersFromWorkspaceRoot` already made the workspace root's specifier decide which version a missing *required* peer is installed at; the optional-peer picker ignored it and always took the highest version present anywhere in the graph. In a workspace whose root pins `postcss: 8.5.10`, an importer that depends on `webpack` and declares no `postcss` of its own got `postcss@8.5.22` hoisted for `terser-webpack-plugin`'s optional `postcss` peer, leaving two `postcss@8.5.x` instances in the graph [#13320](https://github.com/pnpm/pnpm/issues/13320).

- A missing optional peer dependency is no longer satisfied by a prerelease version that its declared range doesn't accept. `ts-jest`, which declares `@jest/transform` and `jest-util` as optional peers with `^29.0.0 || ^30.0.0`, was bound to `30.0.0-alpha.6` when a `jest` 30 prerelease was elsewhere in the graph, while `jest` itself stayed on 29.

- With `autoInstallPeers: false`, a package's own optional peer dependencies are no longer added to its importer entry in `pnpm-lock.yaml` (and no longer linked into its `node_modules`) when another workspace project happens to resolve a matching version [#13325](https://github.com/pnpm/pnpm/issues/13325).

- `overrides` now also govern peers that pnpm auto-installs. Previously an override only rewrote dependencies declared in a manifest, so a peer nobody declares — installed because `autoInstallPeers` is on — resolved against its declared peer range and could bring in a second copy of the very package the override pinned. For example, with `overrides: { react: npm:react@19.2.0 }` and a lone `lucide-react` dependency, pnpm installed `react@18.3.1`; it now installs the pinned `react@19.2.0` [#13320](https://github.com/pnpm/pnpm/issues/13320).

- `pnpm approve-builds -g` is accepted again, reporting that the command is not supported with global packages rather than failing with `unexpected argument '-g' found`. `approve-builds` was the only command that declared `--global` without its `-g` short form [#13310](https://github.com/pnpm/pnpm/issues/13310).

- The Rust implementation of pnpm has moved from alpha to beta releases.

- A catalog name containing a control character no longer corrupts `pnpm-workspace.yaml`. `pnpm add --save-catalog-name "$(printf 'a\nb')"` (or the same value in `saveCatalogName`) now fails with `ERR_PNPM_WORKSPACE_MANIFEST_WRITER_INVALID_CONTROL_CHARACTER` and leaves the file untouched, matching how the writer already treats `allowBuilds` and `overrides` entries.

- Preserve each direct dependency's locked optional peer context during `pnpm dedupe`.

- Preserve optional peer providers recorded in peer suffixes when `pnpm dedupe` rebuilds a workspace lockfile.

- With `nodeLinker: hoisted`, a workspace project no longer gets its own copy of a dependency whose version already won the workspace-root slot. Only the versions that lost the root slot are nested, matching the pnpm CLI. Previously every project's direct dependency was materialized under that project as well, which gave lifecycle scripts a second copy to run in.

- The Rust engine now warns when `package.json` still declares install settings under the `pnpm` field, which pnpm 10 moved to `pnpm-workspace.yaml`. A project that hasn't migrated its `pnpm.overrides` / `pnpm.packageExtensions` / `pnpm.patchedDependencies` previously saw the settings silently ignored, and only met the downstream symptom. Keys the `pnpm` field never owned are left alone.

- Fixed `pnpm licenses list` and `pnpm licenses ls` parsing and license metadata discovery when using the global virtual store [pnpm/pnpm#13332](https://github.com/pnpm/pnpm/issues/13332) and [pnpm/pnpm#13333](https://github.com/pnpm/pnpm/issues/13333).

- A `pnpm-workspace.yaml` that declares a package pattern whose directory does not exist yet — `packages/*` before the first package is created, say — no longer fails every command with `ERR_PNPM_WORKSPACE_WALK_ERROR`. The pattern now matches no projects, as it does in the JavaScript implementation [#13296](https://github.com/pnpm/pnpm/issues/13296).

- Fixed `catalog:` references failing to resolve when installing through a pnpr server, which errored with "No catalog entry '<name>' was found for catalog 'default'." even though the catalog entry existed. The workspace the server reconstructs from the request has no catalog sections, so the client now sends its catalogs along with the request [#13232](https://github.com/pnpm/pnpm/issues/13232).

- Validate the project's pinned package manager and runtimes before running a command, matching the pnpm CLI:

  - A `packageManager` / `devEngines.packageManager` pin that the running pnpm does not satisfy now fails with `ERR_PNPM_BAD_PM_VERSION` (or `ERR_PNPM_OTHER_PM_EXPECTED` when the project is pinned to another package manager), instead of being silently ignored. The check also runs under corepack, where pnpm cannot switch versions itself, and says so.
  - `devEngines.runtime` / `engines.runtime` entries with `onFail: "error"` or `onFail: "warn"` are validated against the Node.js, Deno, or Bun installed on the system, failing with `ERR_PNPM_BAD_RUNTIME_VERSION`.
  - `pmOnFail` and `runtimeOnFail` are honored as bypasses and can now be passed as `--pm-on-fail=<value>` / `--runtime-on-fail=<value>`, the form the error hints suggest.

  Global commands (`--global`) and commands that do not belong to the project (`store`, `dlx`, `self-update`, …) skip these checks, as does a project pin that only asked pnpm to switch versions when `manage-package-manager-versions` is turned off.

- Arguments after the script or command name now reach the script untouched for `pnpm run`, `pnpm exec`, `pnpm dlx`, and `pnpm with`, matching the JavaScript implementation. Previously `pnpm run build --config.foo=bar` consumed the argument as a pnpm setting instead of forwarding it, and `pnpm run build --silent` handed the script `--reporter=silent` — a token the user never typed [#13302](https://github.com/pnpm/pnpm/issues/13302). Put such flags before the script name (`pnpm run --silent build`) to apply them to pnpm.

- Fixed generated lockfiles to preserve packages' scalar `libc` constraints.

- Preserve a user-provided `TMPDIR` when scripts run with `unsafePerm` enabled; otherwise, continue using the package-local temporary directory.

- Added support for `publishConfig.name`, which publishes a package under a different name than the one its manifest carries in the workspace. Only the published artifact is renamed — dependents, `pnpm-lock.yaml`, and release tooling keep addressing the project by its manifest name — and the new name reaches the packed manifest, the tarball filename, and everything that addresses the package at the registry: the already-published check of `pnpm publish -r`, its registry selection, and the release-planning probes of `pnpm change status` and `pnpm version -r`. This also fixes the changelog of the Rust CLI itself, which is published as `pnpm` from a workspace project named `pacquet`: its release notes were composed under the workspace name and so never made it into the published package [#13345](https://github.com/pnpm/pnpm/issues/13345).

- `pnpm run <script> <args>` now forwards every argument after the script name to the script verbatim, matching the behavior of the JavaScript implementation. Previously the `--` separator was dropped, so `pnpm run test -- --watch` reached the underlying program as `--watch` and failed whenever that program claimed the option itself; arguments spelled like `pnpm run`'s own flags (`-s`, `--if-present`) were also consumed by pnpm instead of reaching the script [#13295](https://github.com/pnpm/pnpm/issues/13295). Pass those flags before the script name (`pnpm run -s test`) to apply them to pnpm.

- `pnpm test`, `pnpm start`, and `pnpm stop` now forward their arguments to the script, matching the pnpm CLI. `pnpm test --watch` and `pnpm start --port 3000` previously failed with a usage error, and `pnpm stop` claimed `--if-present` and `-s` for itself instead of passing them on. As with `pnpm run`, every token after the command name reaches the script verbatim, a `--` separator included.

- Added the `--workspace-root` (`-w`) flag, which runs the command on the root workspace project. `pnpm add -D typescript prettier -w` from a workspace subdirectory now saves to the root `package.json` instead of failing with "unexpected argument '-w' found" [#13031](https://github.com/pnpm/pnpm/issues/13031). Combined with `--recursive`, the flag narrows the run to the root project alone. `-w` may not be used together with `--global`, and may only be used inside a workspace.

- `patchedDependencies` patch files that pnpm applies no longer fail with `ERR_PNPM_PATCH_FAILED`: a hunk whose last line is context in a file with no final newline, and an LF patch against a CRLF file, both apply again [#13322](https://github.com/pnpm/pnpm/issues/13322). A hunk that has drifted from its recorded line numbers is also retried nearby, matching pnpm.

- A peer dependency is now recorded in the lockfile at the version and peer suffix the peer provider actually resolved to. Peers whose provider carried peer suffixes of its own could be recorded against a package instance that no importer installs, leaving an unreachable entry in `snapshots:` and a peer bound to the wrong instance [#13320](https://github.com/pnpm/pnpm/issues/13320).

- A peer dependency that the workspace root already provides is no longer installed a second time. With `resolvePeersFromWorkspaceRoot` enabled (the default), a missing peer is matched against the **workspace root** project's dependencies; it was matched against the dependencies of whichever project was being resolved, so a project that didn't declare the peer itself resolved its own copy from the registry. In [vercel/next.js](https://github.com/vercel/next.js), whose `overrides` pin `react` to a single canary build, this pulled in a second `react` and paired it with `react-dom` from the canary — a combination the pin exists to prevent.

- Under `resolvePeersFromWorkspaceRoot`, a workspace root dependency declared with `link:` or `file:` (or the path form of `workspace:`, such as `workspace:../pkg`) now satisfies another project's missing peer dependency at the linked package's own version, instead of being hoisted as a path. Those specifiers are relative to the project that declares them, so the same specifier reached a different directory — or none — from the project the peer was hoisted into, leaving a broken link. The root now has the same authority over the peer as it has when it declares the package with a version range [#13373](https://github.com/pnpm/pnpm/issues/13373).

- Two `pnpm install` peer-resolution fixes that made large workspaces such as [Astro](https://github.com/withastro/astro) produce a different `pnpm-lock.yaml` than pnpm 11 [#13334](https://github.com/pnpm/pnpm/issues/13334):

  - A package that declares the same name in both `dependencies` and `peerDependencies` no longer gets a nested copy of it when the parent already supplies that name, which is what pnpm does with `autoInstallPeers` disabled. The nested copy hid the peer, so the package was recorded without the peer context it resolves in.
  - A duplicate peer-suffixed variant that collapses into a larger, compatible one now collapses everywhere it is referenced. A variant kept alive by a single consumer's edge no longer lingers in the lockfile.

- Closed the remaining gaps in how unscoped per-registry `.npmrc` settings are pinned to the registry their own source file declared:

  - An inline `cert=` / `key=` written with `\n` escapes now expands to a real multi-line PEM, matching the URL-scoped `//host/:cert=` spelling.
  - `pnpm config get` / `pnpm config list` now report a rescoped credential under the URL-scoped key it was pinned to, instead of the unscoped key it was written as.
  - The deprecation warning names the file it read and lists every setting it pinned, including `tokenHelper`.
  - A credential with no registry of its own is no longer attached to the resolved default registry, which repository config can move. The same rule now covers the `@pnpm/napi` bindings: the `authHeaderByUri` entry written with an empty (`""`) key is pinned to the `registry` / `registries.default` the host passed alongside it, never to a registry the project's `.npmrc` names.

- The projects that run their own lifecycle scripts (`preinstall`, `install`, `postinstall`, `prepare`, …) now match pnpm in every install-family command. A project runs them when the command installs it in full, and — in a workspace the command only partly covers — whenever the command mutates it at all; the workspace root runs them even when the command was pointed at another project, because it is installed in full alongside it. As a result, `pnpm update <pkg>` and `pnpm add <pkg>` in a workspace no longer skip the workspace root's scripts, `pnpm update` at a workspace root no longer runs the other members' scripts, and `pnpm update --latest` no longer runs the project's own scripts (it rewrites named dependency specs, so it is a partial install like `pnpm update <pkg>`) [#13358](https://github.com/pnpm/pnpm/issues/13358).

- Print the script command by default when running a filtered lifecycle script. The command remains hidden with `--silent`.

- Run dependency verification consistently after regenerating a lockfile with `dedupePeers` enabled.

- Aligned the `hoistedDependencies` contents and ordering in `node_modules/.modules.yaml` with pnpm.

- Preserve whether package `libc` metadata uses a string or an array when writing the lockfile.

- A `package.json` that starts with a UTF-8 byte order mark is read again instead of failing with `expected value at line 1 column 1`. Workspace discovery, dependency manifests (including bin linking), tarball extraction, and `pnpm publish` of a pre-built tarball all accept one, matching pnpm [#13311](https://github.com/pnpm/pnpm/issues/13311). A manifest that really is malformed now reports its path in the error.

- Fixed `ERR_PNPM_BROKEN_LOCKFILE` when installing with a pnpm 10 lockfile that has a `patchedDependencies` section. See pnpm/pnpm#13307.

- `pnpm -r run "/pattern/" --no-bail` no longer exits zero when one of a project's matched scripts fails and a later one passes. The run summary carries a single status per project, and the passing script overwrote the recorded failure.

- Resolution failures now report the error pnpm defines for them. A well-formed range that the registry publishes nothing for fails with `ERR_PNPM_NO_MATCHING_VERSION` — naming the latest release, the other dist-tags, and the `pnpm view <pkg> versions` command that lists the rest — instead of `ERR_PNPM_SPEC_NOT_SUPPORTED_BY_ANY_RESOLVER`. A package the registry doesn't have fails with `ERR_PNPM_FETCH_404` and the "not in the npm registry, or you have no permission to fetch it" hint (plus which authorization header was sent, since a private registry often answers a permission failure with a 404) instead of a bare HTTP-client message. A wrapper that quotes its cause verbatim no longer prints the same sentence twice in the error report.

- Fixed `pnpm add <workspace-package>` to resolve the local package when `linkWorkspacePackages` is enabled.

- `$dep-name` self-references in `overrides` are now resolved against the root manifest's direct dependencies, so an override such as `rolldown: $rolldown` records the concrete specifier in `pnpm-lock.yaml` and no longer fails a frozen install with `ERR_PNPM_OUTDATED_LOCKFILE` [#13314](https://github.com/pnpm/pnpm/issues/13314). A reference to a package that is not a direct dependency fails with `ERR_PNPM_CANNOT_RESOLVE_OVERRIDE_VERSION`, and the deprecated syntax now warns, pointing at catalogs.

- The root project's `pnpm:devPreinstall` script now runs before resolution and linking, as it does in pnpm 11. It is skipped under `--ignore-scripts`, `--lockfile-only` and `--dry-run`, by `pnpm fetch` and `pnpm rebuild`, and by a repeat install that is already up to date. Workspaces that use the hook to prepare state the install depends on — such as [next.js](https://github.com/vercel/next.js), which generates a placeholder `next` bin with it — were left with dependents linked against files that were never created [#13313](https://github.com/pnpm/pnpm/issues/13313).

- The lockfile-verification line now dates a cached verdict — `✓ Lockfile passes supply-chain policies (verified 253ms ago)` — instead of the timeless `(previously verified)` [#13315](https://github.com/pnpm/pnpm/issues/13315).

- `pnpm install`, `run`, `test`, `update`, `remove`, `link`, `unlink`, `prune`, and `rebuild` now print the workspace scope they resolved — `Scope: all 41 workspace projects`, or `Scope: 5 of 41 workspace projects` under a `--filter`. This is the confirmation that a filter selected what was intended [#13315](https://github.com/pnpm/pnpm/issues/13315).

- An install that blocks a dependency's build scripts now appends a placeholder for it to `pnpm-workspace.yaml`, so approving or denying the build is an edit rather than writing the block by hand:

  ```yaml
  allowBuilds:
    es5-ext: set this to true or false
  ```

  A placeholder is not a decision — the build stays blocked until it is replaced with `true` or `false` — and an existing entry is never overwritten [#13315](https://github.com/pnpm/pnpm/issues/13315).

- Fixed `--parallel` being treated as the script name when placed before `run` in a recursive command.

- `scriptShell` now selects the shell for lifecycle scripts too — dependency build scripts and a project's own `preinstall`/`install`/`postinstall`/`prepare` and `pnpm:devPreinstall` — not only for `pnpm run` and `pnpm exec`. A workspace that configures a shell was still getting the platform default (`sh` / `cmd`) for everything the install itself spawns.

- Fixed `shamefullyHoist: true` to create public root dependency links.

- Prevented optional peers from being selected from an unrelated workspace package's shared dependency context.

- The store index now keys URL, git-host, and `type: git` dependencies by their bare resolution id, matching the key pnpm 11 writes [#13365](https://github.com/pnpm/pnpm/issues/13365). Previously these rows carried a `<name>@` prefix, so a store warmed by one pnpm major was cold for the other and every non-registry dependency was re-downloaded, re-extracted, and re-imported on a switch. A remote tarball also occupied two index rows instead of one, doubling its extraction work.

- A project that pins pnpm through `devEngines.packageManager` (or a v12+ `packageManager` field) now gets its `packageManagerDependencies` recorded in `pnpm-lock.yaml` by every command, not just by the install-family ones [#13348](https://github.com/pnpm/pnpm/issues/13348). Running `pnpm list` (or any other command) in a freshly cloned project no longer leaves the lockfile without the pinned version. The `pmOnFail` setting now also decides whether the pin is recorded: `--pm-on-fail=ignore` keeps it out of the lockfile even when the manifest asks for a stricter policy, and vice versa.

- Fixed `pnpm licenses list` to detect licenses from license files and preserve the latest package version's development classification.

- `pnpm update --latest` now rewrites `jsr:` dependencies. The manifest keeps the protocol and the range operator it declared, so `jsr:1.0.0` becomes `jsr:2.0.0` and `jsr:@scope/name@^1.0.0` becomes `jsr:@scope/name@^2.0.0`, instead of being left at the old version [#13363](https://github.com/pnpm/pnpm/issues/13363).

- `pnpm update --latest` now rewrites dependencies using a named registry alias. The manifest keeps the alias prefix and the range operator it declared, so `gh:1.0.0` becomes `gh:2.0.0` and `gh:@acme/foo@^1.0.0` becomes `gh:@acme/foo@^2.0.0`, instead of being left at the old version [pnpm/pnpm#13393](https://github.com/pnpm/pnpm/issues/13393).

- **Breaking change from pnpm v11.** Under `engineStrict`, an install fails when an incompatible package is reached through a regular `dependencies` edge of an installable package, even when that whole subtree hangs off an `optionalDependencies` entry. pnpm v11 installs the package and emits an install-check warning instead. Packages reachable only through optional edges, or through a package that was itself skipped, are still skipped in both versions [#13286](https://github.com/pnpm/pnpm/issues/13286).

- A lockfile entry whose tarball resolution records no `integrity` is now reported by the lockfile-verification gate, before anything is downloaded: every offending entry is listed in one `ERR_PNPM_MISSING_TARBALL_INTEGRITY` error instead of failing the install one fetch at a time after the gate had already passed the lockfile [#13364](https://github.com/pnpm/pnpm/issues/13364). An `integrity: ''` that pins nothing is treated the same as a missing one, and the exemption for git-host archive URLs is now read from the URL rather than the lockfile's own `gitHosted` marker.

- Fixed `pnpm licenses list` to read licenses from legacy package manifest fields.

- Fixed `--workspace-root` (`-w`) selecting the current workspace when `--dir` pointed at a nonexistent directory outside it (for example `pnpm --dir ../../elsewhere add -w foo`). The command now fails with `ERR_PNPM_NOT_IN_WORKSPACE`, matching pnpm. A nonexistent `--dir` inside the workspace still resolves to the workspace root as before.
