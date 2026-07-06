# GitHub Release Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build tested Windows MSI and macOS DMG installers on GitHub-hosted runners and publish both assets in one tag- or manually-triggered GitHub Release.

**Architecture:** Make the application version an overridable Gradle property with a checked-in `0.1.1` default and a macOS-safe native bundle mapping. Add one least-privilege GitHub Actions workflow whose metadata, test, native package, and publish jobs form a strict dependency chain; lock its important contracts with repository tests.

**Tech Stack:** GitHub Actions, GitHub CLI, Gradle 9.4.1, Kotlin DSL, JDK 21, Compose Multiplatform Desktop `jpackage`, JUnit 5.

---

## File Structure

- Modify `desktop-viewer/build.gradle.kts`: define the default application version and allow CI to override it with `-PappVersion`.
- Modify `desktop-viewer/desktop-app/build.gradle.kts`: use the project version for native packages and derive a positive-leading macOS package version.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt`: lock the default, property override, package wiring, and macOS normalization source contracts.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`: lock release triggers, job dependencies, permissions, runners, package tasks, and asset names.
- Create `.github/workflows/release.yml`: validate release metadata, test the repository, build MSI/DMG packages, and create/update the GitHub Release.

### Task 1: Parameterize the application version

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt`
- Modify: `desktop-viewer/build.gradle.kts`
- Modify: `desktop-viewer/desktop-app/build.gradle.kts`

- [ ] **Step 1: Replace the hard-coded version test with failing configuration contracts**

Replace `ApplicationVersionTest.kt` with:

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationVersionTest {
    private val projectBuildScript = Files.readString(Path.of("../build.gradle.kts"))
    private val desktopBuildScript = Files.readString(Path.of("build.gradle.kts"))

    @Test
    fun `project version defaults to 0_1_1 and accepts the appVersion property`() {
        assertTrue(projectBuildScript.contains("""val defaultAppVersion = "0.1.1""""))
        assertTrue(
            projectBuildScript.contains(
                """providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)""",
            ),
        )
        assertTrue(projectBuildScript.contains("version = appVersion"))
    }

    @Test
    fun `desktop distributions use the resolved project version`() {
        assertTrue(desktopBuildScript.contains("val appVersion = project.version.toString()"))
        assertTrue(desktopBuildScript.contains("packageVersion = appVersion"))
        assertFalse(desktopBuildScript.contains("""packageVersion = "0.1.1""""))
    }

    @Test
    fun `macOS package versions discard leading zero components`() {
        assertTrue(desktopBuildScript.contains("fun macOsPackageVersion(version: String): String"))
        assertTrue(desktopBuildScript.contains("indexOfFirst { component -> component.toIntOrNull()?.let { it > 0 } == true }"))
        assertTrue(desktopBuildScript.contains("numericComponents.drop(firstPositiveIndex).joinToString"))
        assertTrue(desktopBuildScript.contains("packageBuildVersion = macVersion"))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ApplicationVersionTest' --console=plain
```

Expected: `ApplicationVersionTest` fails because the build scripts still hard-code `0.1.1`.

- [ ] **Step 3: Add the overridable root project version**

Replace `desktop-viewer/build.gradle.kts` with:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val defaultAppVersion = "0.1.1"
val appVersion = providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)

allprojects {
    group = "dev.agentperf"
    version = appVersion
}
```

- [ ] **Step 4: Wire native distributions to the resolved version**

Add these declarations immediately after the `plugins` block in
`desktop-viewer/desktop-app/build.gradle.kts` so the Gradle `plugins` block
remains the first executable block:

```kotlin
val appVersion = project.version.toString()

fun macOsPackageVersion(version: String): String {
    val numericComponents = version.split(".")
    val firstPositiveIndex =
        numericComponents.indexOfFirst { component -> component.toIntOrNull()?.let { it > 0 } == true }
    return when (firstPositiveIndex) {
        -1 -> "1"
        0 -> version
        else -> numericComponents.drop(firstPositiveIndex).joinToString(".")
    }
}
```

Replace the native distribution version block with:

```kotlin
            packageVersion = appVersion
            macOS {
                // jpackage requires a positive first component for macOS app images.
                val macVersion = macOsPackageVersion(appVersion)
                packageVersion = macVersion
                packageBuildVersion = macVersion
            }
```

- [ ] **Step 5: Run focused tests with default and overridden versions**

Run:

```bash
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.ApplicationVersionTest' \
  --console=plain
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.ApplicationVersionTest' \
  -PappVersion=0.2.0 \
  --console=plain
./gradlew properties -PappVersion=0.2.0 --console=plain |
  grep '^version: 0.2.0$'
```

Expected: both test commands pass and the final command prints
`version: 0.2.0`.

- [ ] **Step 6: Commit the version configuration**

```bash
git add desktop-viewer/build.gradle.kts \
  desktop-viewer/desktop-app/build.gradle.kts \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationVersionTest.kt
git commit -m "Let release builds supply one authoritative version" \
  -m "Constraint: macOS jpackage rejects versions whose first numeric component is zero." \
  -m "Rejected: Rewrite build files in CI | Mutating sources during packaging is fragile and irreproducible." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: ApplicationVersionTest with default and overridden appVersion values"
```

### Task 2: Lock the release workflow contract

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`

- [ ] **Step 1: Write the failing workflow contract test**

Create `ReleaseWorkflowTest.kt`:

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseWorkflowTest {
    private val workflowPath = Path.of("../../.github/workflows/release.yml")

    @Test
    fun `release workflow supports tag and manual releases`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("tags:"))
        assertTrue(workflow.contains("- \"v*\""))
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("version:"))
        assertTrue(workflow.contains("""^v?[0-9]+\.[0-9]+\.[0-9]+${'$'}"""))
    }

    @Test
    fun `tests gate both native package jobs and publishing`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("test:"))
        assertTrue(workflow.contains("./desktop-viewer/gradlew -p desktop-viewer test --no-daemon"))
        assertTrue(workflow.contains("package-windows:"))
        assertTrue(workflow.contains("runs-on: windows-latest"))
        assertTrue(workflow.contains(":desktop-app:packageMsi"))
        assertTrue(workflow.contains("package-macos:"))
        assertTrue(workflow.contains("runs-on: macos-14"))
        assertTrue(workflow.contains(":desktop-app:packageDmg"))
        assertTrue(workflow.contains("needs: [resolve, test]"))
        assertTrue(
            workflow.contains(
                "needs: [resolve, test, package-windows, package-macos]",
            ),
        )
    }

    @Test
    fun `publish job has write access and stable native asset names`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("permissions:\n  contents: read"))
        assertTrue(workflow.contains("permissions:\n      contents: write"))
        assertTrue(workflow.contains("AgentPerf-Inspector-${'$'}VERSION-windows-x64.msi"))
        assertTrue(workflow.contains("AgentPerf-Inspector-${'$'}VERSION-macos-x64.dmg"))
        assertTrue(workflow.contains("gh release create"))
        assertTrue(workflow.contains("gh release upload"))
        assertTrue(workflow.contains("--clobber"))
    }

    @Test
    fun `workflow uses JDK 21 Gradle caching and official artifact actions`() {
        val workflow = Files.readString(workflowPath)

        assertTrue(workflow.contains("uses: actions/checkout@v6"))
        assertTrue(workflow.contains("uses: actions/setup-java@v4"))
        assertTrue(workflow.contains("java-version: \"21\""))
        assertTrue(workflow.contains("cache: gradle"))
        assertTrue(workflow.contains("uses: actions/upload-artifact@v4"))
        assertTrue(workflow.contains("uses: actions/download-artifact@v5"))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ReleaseWorkflowTest' --console=plain
```

Expected: all four tests fail with `NoSuchFileException` because
`.github/workflows/release.yml` does not exist.

### Task 3: Implement the GitHub Release workflow

**Files:**
- Create: `.github/workflows/release.yml`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt`

- [ ] **Step 1: Create the workflow**

Create `.github/workflows/release.yml`:

```yaml
name: Release native installers

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:
    inputs:
      version:
        description: "Semantic version to publish, for example 0.1.1"
        required: true
        type: string

permissions:
  contents: read

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

jobs:
  resolve:
    name: Resolve release metadata
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.release.outputs.version }}
      tag: ${{ steps.release.outputs.tag }}
      manual: ${{ steps.release.outputs.manual }}
    steps:
      - name: Validate version
        id: release
        shell: bash
        env:
          INPUT_VERSION: ${{ inputs.version }}
        run: |
          set -euo pipefail
          if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" ]]; then
            raw_version="${INPUT_VERSION}"
            manual=true
          else
            raw_version="${GITHUB_REF_NAME}"
            manual=false
          fi

          if [[ ! "${raw_version}" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::Version must use numeric semantic version format, for example v0.1.1."
            exit 1
          fi

          version="${raw_version#v}"
          tag="v${version}"
          if [[ "${GITHUB_EVENT_NAME}" == "push" && "${GITHUB_REF_NAME}" != "${tag}" ]]; then
            echo "::error::Release tag does not match normalized version ${tag}."
            exit 1
          fi

          echo "version=${version}" >> "${GITHUB_OUTPUT}"
          echo "tag=${tag}" >> "${GITHUB_OUTPUT}"
          echo "manual=${manual}" >> "${GITHUB_OUTPUT}"

  test:
    name: Test
    needs: resolve
    runs-on: ubuntu-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@v6
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle
          cache-dependency-path: |
            desktop-viewer/*.gradle.kts
            desktop-viewer/gradle/libs.versions.toml
            desktop-viewer/gradle/wrapper/gradle-wrapper.properties
            desktop-viewer/**/build.gradle.kts
      - name: Run tests
        run: ./desktop-viewer/gradlew -p desktop-viewer test --no-daemon

  package-windows:
    name: Package Windows MSI
    needs: [resolve, test]
    runs-on: windows-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@v6
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle
          cache-dependency-path: |
            desktop-viewer/*.gradle.kts
            desktop-viewer/gradle/libs.versions.toml
            desktop-viewer/gradle/wrapper/gradle-wrapper.properties
            desktop-viewer/**/build.gradle.kts
      - name: Build MSI
        shell: pwsh
        run: |
          $version = "${{ needs.resolve.outputs.version }}"
          & .\desktop-viewer\gradlew.bat -p desktop-viewer :desktop-app:packageMsi "-PappVersion=$version" --no-daemon
      - name: Prepare MSI asset
        shell: pwsh
        env:
          VERSION: ${{ needs.resolve.outputs.version }}
        run: |
          $packages = @(Get-ChildItem "desktop-viewer\desktop-app\build\compose\binaries\main\msi\*.msi")
          if ($packages.Count -ne 1) {
            throw "Expected exactly one MSI package, found $($packages.Count)."
          }
          $VERSION = $env:VERSION
          New-Item -ItemType Directory -Force "release-assets" | Out-Null
          Copy-Item $packages[0].FullName "release-assets\AgentPerf-Inspector-$VERSION-windows-x64.msi"
      - name: Upload MSI
        uses: actions/upload-artifact@v4
        with:
          name: windows-installer
          path: release-assets/AgentPerf-Inspector-${{ needs.resolve.outputs.version }}-windows-x64.msi
          if-no-files-found: error

  package-macos:
    name: Package macOS DMG
    needs: [resolve, test]
    runs-on: macos-14
    steps:
      - name: Check out repository
        uses: actions/checkout@v6
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle
          cache-dependency-path: |
            desktop-viewer/*.gradle.kts
            desktop-viewer/gradle/libs.versions.toml
            desktop-viewer/gradle/wrapper/gradle-wrapper.properties
            desktop-viewer/**/build.gradle.kts
      - name: Build DMG
        run: ./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDmg -PappVersion=${{ needs.resolve.outputs.version }} --no-daemon
      - name: Prepare DMG asset
        shell: bash
        env:
          VERSION: ${{ needs.resolve.outputs.version }}
        run: |
          set -euo pipefail
          packages=(desktop-viewer/desktop-app/build/compose/binaries/main/dmg/*.dmg)
          if [[ "${#packages[@]}" -ne 1 || ! -f "${packages[0]}" ]]; then
            echo "::error::Expected exactly one DMG package."
            exit 1
          fi
          mkdir -p release-assets
          cp "${packages[0]}" "release-assets/AgentPerf-Inspector-$VERSION-macos-x64.dmg"
      - name: Upload DMG
        uses: actions/upload-artifact@v4
        with:
          name: macos-installer
          path: release-assets/AgentPerf-Inspector-${{ needs.resolve.outputs.version }}-macos-x64.dmg
          if-no-files-found: error

  publish:
    name: Publish GitHub Release
    needs: [resolve, test, package-windows, package-macos]
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: Download installers
        uses: actions/download-artifact@v5
        with:
          path: release-assets
          merge-multiple: true
      - name: Create or update release
        shell: bash
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ needs.resolve.outputs.tag }}
          VERSION: ${{ needs.resolve.outputs.version }}
          MANUAL: ${{ needs.resolve.outputs.manual }}
        run: |
          set -euo pipefail
          assets=(release-assets/*)
          if [[ "${#assets[@]}" -ne 2 ]]; then
            echo "::error::Expected exactly two native installer assets."
            exit 1
          fi

          if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
            gh release upload "$TAG" "${assets[@]}" --clobber --repo "$GITHUB_REPOSITORY"
          elif [[ "$MANUAL" == "true" ]]; then
            gh release create "$TAG" "${assets[@]}" \
              --target "$GITHUB_SHA" \
              --title "AgentPerf Inspector $VERSION" \
              --generate-notes \
              --repo "$GITHUB_REPOSITORY"
          else
            gh release create "$TAG" "${assets[@]}" \
              --verify-tag \
              --title "AgentPerf Inspector $VERSION" \
              --generate-notes \
              --repo "$GITHUB_REPOSITORY"
          fi
```

- [ ] **Step 2: Run the workflow contract test and verify GREEN**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ReleaseWorkflowTest' --console=plain
```

Expected: all `ReleaseWorkflowTest` tests pass.

- [ ] **Step 3: Parse the workflow as YAML**

Run:

```bash
ruby -e 'require "yaml"; YAML.parse_file(".github/workflows/release.yml"); puts "release.yml: valid YAML"'
```

Expected:

```text
release.yml: valid YAML
```

- [ ] **Step 4: Commit the tested release workflow**

```bash
git add .github/workflows/release.yml \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ReleaseWorkflowTest.kt
git commit -m "Publish native installers from verified release builds" \
  -m "Constraint: MSI and DMG packages require matching GitHub-hosted operating systems." \
  -m "Rejected: Publish directly from one matrix job | A separate publish gate prevents partial releases." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Directive: Keep contents write permission isolated to the publish job." \
  -m "Tested: ReleaseWorkflowTest and Ruby YAML parser"
```

### Task 4: Verify the complete release path locally

**Files:**
- Verify: `.github/workflows/release.yml`
- Verify: `desktop-viewer/build.gradle.kts`
- Verify: `desktop-viewer/desktop-app/build.gradle.kts`

- [ ] **Step 1: Run the complete repository test suite**

Run:

```bash
cd desktop-viewer
./gradlew test --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 2: Build a default-version macOS DMG**

Run:

```bash
./gradlew :desktop-app:packageDmg --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL` and exactly one DMG under
`desktop-app/build/compose/binaries/main/dmg/`.

- [ ] **Step 3: Build an overridden-version macOS DMG**

Run:

```bash
./gradlew :desktop-app:packageDmg \
  -PappVersion=0.2.0 \
  --no-daemon \
  --console=plain
plutil -extract CFBundleShortVersionString raw -o - \
  "desktop-app/build/compose/binaries/main/app/AgentPerf Inspector.app/Contents/Info.plist"
```

Expected: `BUILD SUCCESSFUL`; the produced app bundle uses a positive-leading
macOS version, and `plutil` prints `2.0` while the Gradle project version is
`0.2.0`.

- [ ] **Step 4: Inspect package and repository state**

Run:

```bash
find desktop-app/build/compose/binaries/main/dmg -maxdepth 1 -type f -name '*.dmg' -print
git diff --check
git status --short --branch
git log -3 --format=full
```

Expected: DMG output is present, `git diff --check` reports no whitespace
errors, the working tree contains only the implementation-plan file if it has
not yet been committed, and both implementation commits contain Lore trailers.

- [ ] **Step 5: Commit the implementation plan**

From the repository root:

```bash
git add docs/superpowers/plans/2026-07-05-github-release-workflow.md
git commit -m "Preserve the verified release implementation procedure" \
  -m "Constraint: Windows packaging can only receive authoritative verification on the GitHub Windows runner." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: Full Gradle tests, local DMG packaging, YAML parsing, and workflow contract tests" \
  -m "Not-tested: Windows MSI job and GitHub Release publication until the workflow is pushed and triggered."
```

## Remote Verification

Pushing the implementation alone does not publish a release because the
workflow ignores branch pushes. Authoritative remote verification is:

```bash
git push origin main
git tag v0.1.1
git push origin v0.1.1
gh run watch --exit-status
gh release view v0.1.1
```

Creating and pushing the release tag is an external publication action and must
only be performed when explicitly requested.
