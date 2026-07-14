# Unified Desktop Home Design

> **Directory-boundary amendment (2026-07-14):** Layout Inspector code now belongs to
> `layout-inspector/` and uses the `:layout-inspector:*` Gradle namespace.
> Simpleperf remains under `simpleperf-viewer/`. The application shell now lives at root
> `desktop-app/`, outside both feature directories, and Layout Inspector exposes the explicit
> `:layout-inspector:presentation` entry module.
> References below to the former root Layout Inspector modules describe the pre-move design and
> must not be used to recreate scattered root modules.

## Goal

Turn the Layout Inspector and Simpleperf CPU Profiler into one Compose Desktop application with one Gradle build, one native package, and one window. The application opens on a home page with two feature entries. Selecting an entry opens that feature in the current window, and every feature page provides a persistent way back to the home page.

## Success Criteria

- `desktop-app` is the only executable and native packaging module.
- The application opens on the home page.
- The home page contains visible entries for Layout Inspector and Simpleperf CPU Profiler.
- Selecting either entry renders the existing feature UI in the same window.
- A fixed top navigation bar returns from either feature to the home page.
- Returning to the home page does not discard an already-created feature workspace during the same application session.
- Simpleperf implementation remains visibly owned by `simpleperf-viewer/`.
- Layout Inspector and Simpleperf modules do not depend on each other's implementation modules.
- Root tests, checks, and native packaging cover the unified application.

## Architecture

### Single Gradle build

The root `settings.gradle.kts` will include every Simpleperf module with a namespaced Gradle path under `:features:simpleperf-viewer`. For example:

- `:features:simpleperf-viewer:profile-model`
- `:features:simpleperf-viewer:application`
- `:features:simpleperf-viewer:presentation`
- `:features:simpleperf-viewer:app-desktop`

The physical directories remain under `simpleperf-viewer/`, so ownership is visible in both the filesystem and Gradle project paths.

The nested Simpleperf Gradle root files and wrapper will be removed after their configuration is migrated to the root build. Plugin and dependency versions will be resolved by the root version catalog. Kotlin, Compose, test, lint, and static-analysis configuration will use one compatible version set.

### Module boundaries

`desktop-app` is the application shell. It may depend on the public UI entry modules of both features:

- Layout Inspector through its existing root modules.
- Simpleperf through `:features:simpleperf-viewer:app-desktop`.

Simpleperf modules may depend only on other modules inside the Simpleperf feature tree and third-party libraries. Existing Layout Inspector modules may not depend on Simpleperf modules. No Simpleperf classes will be moved into Layout Inspector packages.

The Simpleperf `app-desktop` module becomes an embeddable UI composition module rather than a second executable. It retains dependency assembly and exposes a composable workspace entry point.

## Application shell and navigation

The application shell owns a small, explicit route state:

```text
HOME
LAYOUT_INSPECTOR
SIMPLEPERF
```

The initial route is `HOME`.

### Home page

The home page presents two large feature cards:

1. **Layout Inspector** — inspect Android View hierarchy, screenshots, bounds, and properties.
2. **Simpleperf CPU Profiler** — capture or open Simpleperf sessions and analyze CPU samples.

Each card has a title, short description, and primary action. The page uses the application shell theme and does not initialize device capture work by itself.

### Feature page frame

Both feature routes render inside a shared frame:

- A fixed top bar shows the product name, current feature title, and a `返回主页` action.
- The selected feature fills the remaining window area.
- Returning home changes only the shell route.
- Feature composables remain in the composition during route changes by using retained, keyed content visibility. Their remembered controllers and open reports therefore survive a temporary return to the home page.

The native application menu remains owned by `desktop-app`. Layout-specific settings requests are delivered only to the Layout Inspector workspace.

## Feature integration

### Layout Inspector

`DesktopViewerApp` remains the Layout Inspector workspace. The application shell wraps it without changing its domain, ADB, capture, archive, or analysis behavior.

The shell continues to provide the `FrameWindowScope` required by the existing viewer and its native menu integrations.

### Simpleperf CPU Profiler

The current Simpleperf `main()` composition is split into:

- `SimpleperfWorkspace`, an embeddable composable containing dependency creation, controllers, state collection, actions, and `HomeScreen`.
- The shared root application `main()`, which is the only process and window owner.

`SimpleperfWorkspace` receives the current `ComposeWindow` when desktop file dialogs require it. Its controller refresh begins only when the workspace first enters composition. Existing capture, import, report, export, toolchain, and error behavior remains unchanged.

The former Simpleperf `main()` and native distribution configuration are removed because the feature is no longer independently executable or packaged.

## State and lifecycle

- The shell route is stored with `rememberSaveable` or an equivalent stable Compose state.
- Each feature workspace is initialized at most once per application session.
- Navigating home does not cancel active work solely because the route changes.
- Closing the application disposes both workspaces and their resources through their existing Compose disposal hooks.
- A feature failure stays inside that feature UI and does not prevent navigation back to the home page.

## Packaging

`desktop-app` remains the sole Compose Desktop application plugin owner and produces the existing `AndroidPerfermanceStudio` native identity.

Its runtime image must include modules required by both features:

- `java.net.http` for Layout Inspector AI transport initialization.
- `java.sql` for Simpleperf SQLite storage.

The old Simpleperf application image, distribution tasks, and root proxy tasks are removed. Documentation and CI use the root application tasks only.

## Testing strategy

Implementation follows test-first development.

### Navigation tests

- Default route is `HOME`.
- Selecting each home entry changes to the expected feature route.
- `返回主页` changes either feature route back to `HOME`.
- Re-entering a feature reuses its retained workspace identity.

### Build-boundary tests

- Root settings include namespaced Simpleperf modules and no composite build.
- `desktop-app` depends on the Simpleperf UI composition module.
- Simpleperf Gradle files do not reference Layout Inspector modules.
- The nested Simpleperf settings, wrapper, independent `main()`, and distribution configuration are absent.

### Regression and verification

- Run all root unit tests and Simpleperf tests from one root invocation.
- Run lint and static analysis configured for the unified build.
- Create the single native application image.
- Launch the packaged application and verify the process remains alive without startup errors.
- Verify both home entries and both return actions through focused UI/state tests; perform a manual navigation smoke when UI automation cannot cover native window behavior.

## Migration sequence

1. Add failing tests for shell navigation and unified build boundaries.
2. Register Simpleperf modules in the root Gradle build with namespaced paths.
3. Align plugins, JVM targets, dependencies, lint, and analysis configuration.
4. Convert Simpleperf `app-desktop` from an executable into an embeddable workspace module.
5. Add the application shell, home page, feature frame, and retained navigation.
6. Add Simpleperf runtime modules to the unified native package.
7. Remove composite-build proxy tasks and nested Gradle-root artifacts.
8. Update documentation and release checks for the single application.
9. Run complete tests, checks, packaging, and startup verification.

## Non-goals

- Sharing domain models between Layout Inspector and Simpleperf.
- Combining their ADB gateways or controller implementations.
- Redesigning either existing feature workflow.
- Adding deep links, multiple windows, tabs, or cross-feature data transfer.
- Shipping separate native installers for the two features.

## Risks and controls

- **Kotlin or Compose version mismatch:** use one root version catalog and compile every module in the same build.
- **Gradle project-name collisions:** namespace every Simpleperf module below `:features:simpleperf-viewer`.
- **Premature device work on the home page:** initialize feature controllers only when their workspace first enters composition.
- **State loss when returning home:** retain keyed feature compositions after first visit.
- **Native runtime omissions:** regression-test both `java.net.http` and `java.sql` packaging declarations.
- **Boundary erosion:** enforce dependency-direction assertions in build tests and retain the feature directory boundary.
