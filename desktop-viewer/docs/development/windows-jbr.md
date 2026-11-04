# Windows JBR Title Bar

The main Windows window embeds the existing Swing menu bar into a JBR custom
title bar. The menu model, page actions, recent files and accelerators remain
owned by `ui-components/WindowMenu.kt`. JBR owns caption buttons and native
title-bar behavior; the application reserves their insets and marks only menu
targets as interactive. Device/process selectors remain inline on Windows.

## Runtime And Fallback

`jbr-runtime-manifest.json` pins the Windows x64 JBRSDK archive, its release
identity, SHA-256 and upstream SHA-512. The SDK variant does not include JCEF.
The SDK is needed for `jlink` and `jpackage`; installers contain a reduced
runtime, not a separately installed user JDK.

`WindowsWindowChrome` checks the operating system and JBR service availability.
Missing or unsupported services keep the standard title bar and menu bar. Failed
attachments restore the original Swing UI, layout, colors and listeners. macOS
and Linux retain their current window behavior and release runtimes.

The client dependency is `org.jetbrains.runtime:jbr-api:1.5.0`, the sufficient
custom-title-bar API surface. ProGuard must retain its service and method names.
Runtime support is determined by capability checks, not version-string guesses.

## Local Windows Development

Run from the repository root in PowerShell 7:

```powershell
./scripts/install-jbr.ps1
$jbr = (Resolve-Path ./build/jbr/windows-x64).Path
$env:JAVA_HOME = $jbr
$env:PATH = "$jbr/bin;$env:PATH"
./desktop-viewer/gradlew.bat -p desktop-viewer :desktop-app:run "-Ptarget.javaHome=$jbr"
```

The installer requires a fresh destination and never overwrites an existing JDK.
For another installation, pass `-Destination <new-path>` and use that path as
`JAVA_HOME`. Checksum or identity failures do not silently use a JDK from `PATH`.

## Validation

The Windows release job installs the pinned SDK, runs the title-bar and runtime
regressions under it, and passes its home explicitly to MSI/EXE packaging. A
required native smoke test must fail, rather than skip, when JBR support or the
Windows desktop is unavailable.

Portable tests cover fallback, rollback, attachment/replacement/disposal,
caption insets, font growth, dark-theme pixels, live page actions, localization,
checkbox state and menu accelerators. A rendered Swing strip is not proof of
Windows-native behavior. Before release acceptance, verify on Windows 11:

- Open menus with mouse, Alt/F10 and accelerators; navigate nested/disabled items.
- Drag only empty/title regions; menus must not move the window.
- Resize, maximize, restore and double-click the title region.
- Hover maximize for Snap Layouts; use edge snapping and Win+Arrow.
- Switch mixed-DPI monitors and test 100%, 125%, 150% and 200% scaling.
- Check light/dark themes, Chinese/English, long titles and menu popup placement.
- Repeat the smoke check from an installed MSI/EXE, not only the full SDK.

When updating JBR, verify the new upstream archive and checksums, update the
manifest and corresponding regression expectations, retain runtime license/legal
files in the distribution, and rerun the Windows gates. The pinned source commit
and upstream release URL in the manifest identify the runtime's source provenance.
