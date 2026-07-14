# All Native Package Formats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish all six x64 installer formats supported by Compose Desktop across Linux, Windows, and macOS.

**Architecture:** Keep one packaging job per operating system and build both native formats supported by that host. Enforce a stable six-file release contract through Gradle configuration tests, workflow tests, per-format preparation checks, and a final publication allowlist.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Compose Multiplatform Desktop native distributions, GitHub Actions, JUnit 5, Bash, PowerShell.

---

## File Structure

- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt` to lock the six-format Gradle target contract.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt` to lock the three-platform, six-artifact workflow contract.
- Modify `desktop-viewer/desktop-app/build.gradle.kts` to enable DEB, RPM, MSI, EXE, DMG, and PKG.
- Modify `.github/workflows/release.yml` to build, validate, upload, and publish all six packages.

### Task 1: Lock the Complete Gradle Format Contract

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt`

- [ ] **Step 1: Write the failing target-format test**

Add this test:

```kotlin
@Test
fun `desktop distributions enable every supported native installer format`() {
    listOf("Deb", "Rpm", "Msi", "Exe", "Dmg", "Pkg").forEach { format ->
        assertTrue(
            desktopBuildScript.contains("TargetFormat.$format"),
            "Missing TargetFormat.$format",
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests \
  'dev.agentperf.desktop.ApplicationVersionTest.desktop distributions enable every supported native installer format' \
  --rerun-tasks --console=plain
```

Expected: FAIL because `TargetFormat.Rpm`, `TargetFormat.Exe`, and `TargetFormat.Pkg` are absent.

- [ ] **Step 3: Stop after the verified failure**

Do not change Gradle configuration until Task 3 so the workflow contract can also be established while the implementation remains red.

### Task 2: Lock the Complete Release Workflow Contract

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`

- [ ] **Step 1: Replace the platform-job test**

Use:

```kotlin
@Test
fun `tests gate all native package jobs and publishing`() {
    val workflow = Files.readString(workflowPath)

    assertTrue(workflow.contains("test:"))
    assertTrue(workflow.contains("./desktop-viewer/gradlew -p desktop-viewer test --no-daemon"))
    assertTrue(workflow.contains("package-linux:"))
    assertTrue(workflow.contains("runs-on: ubuntu-latest"))
    assertTrue(workflow.contains(":desktop-app:packageDeb"))
    assertTrue(workflow.contains(":desktop-app:packageRpm"))
    assertTrue(workflow.contains("package-windows:"))
    assertTrue(workflow.contains("runs-on: windows-latest"))
    assertTrue(workflow.contains(":desktop-app:packageMsi"))
    assertTrue(workflow.contains(":desktop-app:packageExe"))
    assertTrue(workflow.contains("package-macos:"))
    assertTrue(workflow.contains("runs-on: macos-15-intel"))
    assertTrue(workflow.contains(":desktop-app:packageDmg"))
    assertTrue(workflow.contains(":desktop-app:packagePkg"))
    assertTrue(workflow.contains("needs: [resolve, test]"))
    assertTrue(
        workflow.contains(
            "needs: [resolve, test, package-linux, package-windows, package-macos]",
        ),
    )
}
```

- [ ] **Step 2: Replace the stable-asset test**

Use:

```kotlin
@Test
fun `publish job has write access and all stable native asset names`() {
    val workflow = Files.readString(workflowPath)

    assertTrue(workflow.contains("permissions:\n  contents: read"))
    assertTrue(workflow.contains("permissions:\n      contents: write"))
    listOf(
        "AgentPerf-Inspector-${'$'}VERSION-linux-x64.deb",
        "AgentPerf-Inspector-${'$'}VERSION-linux-x64.rpm",
        "AgentPerf-Inspector-${'$'}VERSION-windows-x64.msi",
        "AgentPerf-Inspector-${'$'}VERSION-windows-x64.exe",
        "AgentPerf-Inspector-${'$'}VERSION-macos-x64.dmg",
        "AgentPerf-Inspector-${'$'}VERSION-macos-x64.pkg",
    ).forEach { assetName ->
        assertTrue(workflow.contains(assetName), "Missing $assetName")
    }
    assertTrue(workflow.contains("""if [[ "${'$'}{#assets[@]}" -ne 6 ]]"""))
    assertTrue(workflow.contains("gh release create"))
    assertTrue(workflow.contains("gh release upload"))
    assertTrue(workflow.contains("--clobber"))
}
```

- [ ] **Step 3: Run the workflow tests and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests \
  'dev.agentperf.desktop.ReleaseWorkflowTest' \
  --rerun-tasks --console=plain
```

Expected: FAIL because the Linux job, RPM/EXE/PKG tasks, six assets, and six-asset count do not exist.

### Task 3: Enable All Compose Desktop Target Formats

**Files:**
- Modify: `desktop-viewer/desktop-app/build.gradle.kts`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt`

- [ ] **Step 1: Expand `targetFormats`**

Replace the existing call with:

```kotlin
targetFormats(
    TargetFormat.Deb,
    TargetFormat.Rpm,
    TargetFormat.Msi,
    TargetFormat.Exe,
    TargetFormat.Dmg,
    TargetFormat.Pkg,
)
```

- [ ] **Step 2: Run the format test and verify GREEN**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests \
  'dev.agentperf.desktop.ApplicationVersionTest.desktop distributions enable every supported native installer format' \
  --rerun-tasks --console=plain
```

Expected: PASS.

### Task 4: Build and Upload Two Formats Per Operating System

**Files:**
- Modify: `.github/workflows/release.yml`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`

- [ ] **Step 1: Add the Linux job**

Add `package-linux`, depending on `[resolve, test]`, using x64 `ubuntu-latest` and the existing checkout/JDK setup. Install the RPM toolchain:

```yaml
- name: Install RPM packaging tools
  run: |
    sudo apt-get update
    sudo apt-get install --yes rpm
```

Build both formats:

```yaml
- name: Build DEB and RPM
  run: |
    ./desktop-viewer/gradlew -p desktop-viewer \
      :desktop-app:packageDeb \
      :desktop-app:packageRpm \
      -PappVersion=${{ needs.resolve.outputs.version }} \
      --no-daemon
```

Prepare exact files:

```bash
set -euo pipefail
mkdir -p release-assets
prepare_asset() {
  local format="$1"
  local extension="$2"
  local destination="$3"
  local packages=(desktop-viewer/desktop-app/build/compose/binaries/main/"$format"/*."$extension")
  if [[ "${#packages[@]}" -ne 1 || ! -f "${packages[0]}" ]]; then
    echo "::error::Expected exactly one ${extension^^} package."
    exit 1
  fi
  cp "${packages[0]}" "$destination"
}
prepare_asset deb deb "release-assets/AgentPerf-Inspector-$VERSION-linux-x64.deb"
prepare_asset rpm rpm "release-assets/AgentPerf-Inspector-$VERSION-linux-x64.rpm"
```

Upload both files as artifact `linux-installers`.

- [ ] **Step 2: Expand the Windows job**

Run both Gradle tasks:

```powershell
& .\desktop-viewer\gradlew.bat -p desktop-viewer `
  :desktop-app:packageMsi `
  :desktop-app:packageExe `
  "-PappVersion=$version" `
  --no-daemon
```

Use a PowerShell helper that requires one package per format:

```powershell
function Copy-SinglePackage {
  param(
    [string]$Pattern,
    [string]$Format,
    [string]$Destination
  )
  $packages = @(Get-ChildItem $Pattern)
  if ($packages.Count -ne 1) {
    throw "Expected exactly one $Format package, found $($packages.Count)."
  }
  Copy-Item $packages[0].FullName $Destination
}

New-Item -ItemType Directory -Force "release-assets" | Out-Null
Copy-SinglePackage `
  "desktop-viewer\desktop-app\build\compose\binaries\main\msi\*.msi" `
  "MSI" `
  "release-assets\AgentPerf-Inspector-$env:VERSION-windows-x64.msi"
Copy-SinglePackage `
  "desktop-viewer\desktop-app\build\compose\binaries\main\exe\*.exe" `
  "EXE" `
  "release-assets\AgentPerf-Inspector-$env:VERSION-windows-x64.exe"
```

Upload both files as artifact `windows-installers`.

- [ ] **Step 3: Expand the macOS job and pin x64**

Change the runner from ARM64 `macos-14` to explicit x64 `macos-15-intel`, then build both formats:

```yaml
package-macos:
  runs-on: macos-15-intel

- name: Build DMG and PKG
  run: |
    ./desktop-viewer/gradlew -p desktop-viewer \
      :desktop-app:packageDmg \
      :desktop-app:packagePkg \
      -PappVersion=${{ needs.resolve.outputs.version }} \
      --no-daemon
```

Use the same Bash `prepare_asset` contract for `dmg` and `pkg`, producing the two macOS stable names. Upload both files as artifact `macos-installers`.

- [ ] **Step 4: Enforce the six-file publish contract**

Change publish dependencies to:

```yaml
needs: [resolve, test, package-linux, package-windows, package-macos]
```

Before invoking `gh`, require six files and each exact expected name:

```bash
assets=(release-assets/*)
if [[ "${#assets[@]}" -ne 6 ]]; then
  echo "::error::Expected exactly six native installer assets."
  exit 1
fi
expected_assets=(
  "release-assets/AgentPerf-Inspector-$VERSION-linux-x64.deb"
  "release-assets/AgentPerf-Inspector-$VERSION-linux-x64.rpm"
  "release-assets/AgentPerf-Inspector-$VERSION-windows-x64.msi"
  "release-assets/AgentPerf-Inspector-$VERSION-windows-x64.exe"
  "release-assets/AgentPerf-Inspector-$VERSION-macos-x64.dmg"
  "release-assets/AgentPerf-Inspector-$VERSION-macos-x64.pkg"
)
for asset in "${expected_assets[@]}"; do
  if [[ ! -f "$asset" ]]; then
    echo "::error::Missing expected release asset: $asset"
    exit 1
  fi
done
```

- [ ] **Step 5: Run the workflow tests and verify GREEN**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests \
  'dev.agentperf.desktop.ReleaseWorkflowTest' \
  --rerun-tasks --console=plain
```

Expected: PASS.

### Task 5: Verify the Complete Change

**Files:**
- Verify: `.github/workflows/release.yml`
- Verify: `desktop-viewer/desktop-app/build.gradle.kts`
- Verify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt`
- Verify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`

- [ ] **Step 1: Run the full test suite**

Run:

```bash
cd desktop-viewer
./gradlew test --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build both macOS formats locally**

Run:

```bash
cd desktop-viewer
./gradlew \
  :desktop-app:packageDmg \
  :desktop-app:packagePkg \
  -PappVersion=0.1.1 \
  --rerun-tasks \
  --console=plain
```

Expected: one DMG under `build/compose/binaries/main/dmg` and one PKG under `build/compose/binaries/main/pkg`.

- [ ] **Step 3: Validate YAML and whitespace**

Run:

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/release.yml"); puts "YAML parse OK"'
git diff --check
```

Expected: YAML parse succeeds and `git diff --check` prints no errors.

- [ ] **Step 4: Review final status**

Run:

```bash
git status --short
git diff --stat
```

Expected: only the four planned implementation files and this plan are changed; `.superpowers/` remains untouched.
