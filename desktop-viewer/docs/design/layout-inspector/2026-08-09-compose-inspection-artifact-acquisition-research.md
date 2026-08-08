# Compose Inspection Artifacts Acquisition Research

Date: 2026-08-09

## Conclusion

- **AOSP UI Inspector Agent Bundle:** there is no documented public prebuilt bundle or Google Maven coordinate for the four artifacts required here. AOSP publishes the source and Bazel targets; the auditable acquisition path is therefore to build them at a pinned `platform/tools/base` revision, then publish APS's own signed, checksummed bundle. Do not treat files copied from an Android Studio installation as a stable distribution API.
- **Compose `inspector.jar`:** it is publicly downloadable, but not as an independent Maven artifact. It is the root entry `inspector.jar` inside the exact Compose UI AAR in Google Maven. Resolve the app's exact Compose UI version, download that exact AAR and extract the entry; never substitute a nearby version.

## AOSP UI Inspector Agent Bundle

The pinned upstream source is:

```text
repository: https://android.googlesource.com/platform/tools/base
revision:   b1261356012800c0a93d18f03b060024e8162c2f
subtree:    ui-inspector
tree id:    5e7c481d43e6b4efb622e09ee85fa7c87f4970cb
source:     https://android.googlesource.com/platform/tools/base/+archive/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector.tar.gz
```

The source archive is directly downloadable, but it is **not a binary bundle** and is insufficient by itself: the BUILD files depend on other AOSP Studio projects and prebuilts. Gitiles regenerates archive timestamps, so the `.tar.gz` byte hash is not stable; pin and verify the Git commit/tree rather than relying on an archive SHA-256.

The official BUILD targets produce the required files:

```bash
tools/base/bazel/bazel build \
  //tools/base/ui-inspector/agent/native:lib_ui_inspector_agent.so \
  //tools/base/ui-inspector/agent/service:lib_ui_inspector_service \
  //tools/base/ui-inspector/agent/payload:lib_ui_inspector_payload \
  //tools/base/ui-inspector/agent/inspectors/view:view_inspector_jar
```

Outputs are the per-ABI `lib_ui_inspector_agent.so`, `lib_ui_inspector_service.jar`, `lib_ui_inspector_payload.jar`, and `view-inspector.jar`. APS already codifies the targets, output collection, DEX validation, and SHA-256 manifest in:

```bash
tools/build-compose-agent-bundle.sh /path/to/aosp /path/to/compose-agent-bundle
```

Use a Studio source checkout because upstream Bazel labels reference `prebuilts/studio`, `prebuilts/tools`, `tools/adt/idea`, and other workspace projects:

```bash
mkdir aosp-studio && cd aosp-studio
repo init --partial-clone \
  -u https://android.googlesource.com/platform/manifest \
  -b studio-main
repo sync -c -j8
git -C tools/base fetch https://android.googlesource.com/platform/tools/base \
  b1261356012800c0a93d18f03b060024e8162c2f
git -C tools/base checkout --detach b1261356012800c0a93d18f03b060024e8162c2f
git -C tools/base rev-parse HEAD
```

For a genuinely reproducible release, also save `repo manifest -r` from the known-good workspace or pin the build-container/workspace digest. Pinning only `tools/base` while the manifest's other projects continue to move is not a complete reproducibility lock.

### Public prebuilt status

At the investigated revision, AOSP defines source build targets and the `host:cli` packages those targets as Bazel resources; it does not define a Maven publication or a standalone binary release for this bundle. The official UI Inspector README likewise describes building/deploying the agent and downloading only the Compose AAR from Google Maven. Therefore:

1. **Development/CI:** build from pinned AOSP sources.
2. **APS users:** download an APS-produced signed bundle from APS release storage or receive it embedded in the desktop distribution.
3. **Do not:** scrape an Android Studio installation. Its internal layout is not a public compatibility contract and may contain the Transport/App Inspection agent rather than these standalone UI Inspector CLI artifacts.

## Exact Compose `inspector.jar`

The agent discovers the running app's version from:

```text
META-INF/androidx.compose.ui_ui.version
```

This is also the behavior in AOSP `ComposeInspectionUtils`. If the resource was removed or the app uses a custom/snapshot Compose build, obtain the exact resolved version and repository from the app's Gradle dependency graph. A Compose BOM version, Compose Compiler version, or Kotlin version is not a substitute for the resolved Compose UI artifact version.

### Coordinate mapping

```text
group: androidx.compose.ui

legacy Android artifact: ui
KMP Android artifact:    ui-android
```

The normal mapping is `ui` before the Compose 1.5 KMP split and `ui-android` afterward. There is a prerelease boundary that a plain `major.minor >= 1.5` check misses:

- `1.5.0-alpha01` through `1.5.0-alpha04`: `ui` AAR;
- `1.5.0-beta01` and later 1.5+ releases: `ui-android` AAR.

For robustness, try the expected exact-version coordinate and require an AAR containing root `inspector.jar`; for the 1.5 prereleases, fall back between `ui-android` and `ui` only at the **same version**. Never fall back to another version.

Google Maven URL template:

```text
https://dl.google.com/dl/android/maven2/androidx/compose/ui/{artifact}/{version}/{artifact}-{version}.aar
```

Example for Compose UI `1.11.4`:

```bash
VERSION=1.11.4
ARTIFACT=ui-android
BASE="https://dl.google.com/dl/android/maven2/androidx/compose/ui/$ARTIFACT/$VERSION"

curl --fail --location \
  "$BASE/$ARTIFACT-$VERSION.aar" \
  --output "$ARTIFACT-$VERSION.aar"
curl --fail --location \
  "$BASE/$ARTIFACT-$VERSION.aar.sha256" \
  --output "$ARTIFACT-$VERSION.aar.sha256"

EXPECTED="$(cat "$ARTIFACT-$VERSION.aar.sha256")"
ACTUAL="$(shasum -a 256 "$ARTIFACT-$VERSION.aar" | awk '{print $1}')"
test "$EXPECTED" = "$ACTUAL"

unzip -p "$ARTIFACT-$VERSION.aar" inspector.jar > compose-inspector-$VERSION.jar
test -s compose-inspector-$VERSION.jar
```

For `1.4.3`, use `ARTIFACT=ui`. Google Maven does not publish a separate `inspector.jar` URL or `*-inspector.jar` classifier at these coordinates; extracting the nested AAR entry is required.

Existing local AARs may be reused from Gradle's cache (implementation detail, so search rather than hard-code the final hash directory):

```text
~/.gradle/caches/modules-2/files-2.1/androidx.compose.ui/{artifact}/{version}/{hash}/{artifact}-{version}.aar
```

or Maven Local:

```text
~/.m2/repository/androidx/compose/ui/{artifact}/{version}/{artifact}-{version}.aar
```

## License and release requirements

- `platform/tools/base/ui-inspector` is AOSP code under Apache License 2.0; preserve copyright/license notices and mark APS patches as modified when redistributing binaries or source.
- Compose UI's published POM declares Apache License 2.0, and current AARs include the license. Preserve the license with the extracted inspector.
- The built Agent JARs/native library merge or link transitive dependencies. Generate and ship a third-party NOTICE/SBOM from the pinned build graph; the top-level AOSP license alone is not a complete binary notice audit.
- Publish SHA-256 values, provenance (full manifest plus tools/base commit and APS patch IDs), signatures, supported ABIs, and the source/license offer alongside the APS bundle.

## Primary sources

1. [AOSP UI Inspector README at the pinned revision](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/README.md) — agent layers, attachment, exact Compose version discovery, Google Maven AAR resolution, and `ui`/`ui-android` split.
2. [Native agent BUILD](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/agent/native/BUILD), [service BUILD](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/agent/service/BUILD), [payload BUILD](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/agent/payload/BUILD), and [View Inspector BUILD](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/agent/inspectors/view/BUILD) — authoritative build targets and transitive packaging.
3. [AOSP MavenArtifactResolver](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/host/src/main/java/com/android/tools/ui/inspector/MavenArtifactResolver.kt) — downloads the exact AAR from `maven.google.com` and extracts root `inspector.jar`.
4. [AOSP ComposeInspectionUtils](https://android.googlesource.com/platform/tools/base/+/b1261356012800c0a93d18f03b060024e8162c2f/ui-inspector/agent/payload/src/main/java/com/android/tools/ui/inspector/payload/ComposeInspectionUtils.java) — exact version resource path.
5. [Google Maven Compose UI index](https://dl.google.com/dl/android/maven2/androidx/compose/ui/group-index.xml) — published `ui` and `ui-android` versions, including the 1.5 prerelease boundary.
6. [`ui-android:1.11.4` AAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4.aar), [SHA-256](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4.aar.sha256), and [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4.pom) — downloadable artifact, checksum, and license declaration.
7. [AndroidX inspection Gradle plugin README](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/inspection/inspection-gradle-plugin/README.md) and [Compose UI packaging call](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/ui/ui/build.gradle) — first-party evidence that Compose packages its inspector into the UI AAR.
