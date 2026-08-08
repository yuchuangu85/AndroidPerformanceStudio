# UI Inspector CLI

## What Layout Inspector can offer to the AI agent

Layout Inspector can provide detailed information about the app’s UI
hierarchy. In particular, it can provide compose information that is not
obtainable with any other tool. This includes composable hierarchy,
parameters, modifiers, and file locations.

The AI agent could use this information for debugging purposes and to
navigate the app.

## Current limitations

Layout Inspector is currently designed as a singleton within a Project.
It doesn’t support connecting to multiple devices at the same time or to
multiple apps on the same device.

Layout Inspector is currently constructed using App Inspection and the
Transport layer. Transport, however, introduces a significant and heavy
dependency with extensive functionality that the Layout Inspector does
not actually require. This dependency remains a persistent source of
difficult-to-resolve bugs, which can result in intermittent connection
reliability. Although most connectivity problems have been addressed
over time, certain issues remain unresolved or continue to emerge.

A substantial refactoring of the codebase would be required in order to
expose a reliable tool to the agent.

Furthermore, having a strong dependency on Transport prevents Layout
Inspector from being used outside of Android Studio. A lot of
development now happens in CLIs and non-specialized tools. Android
developers using these would benefit from an Android UI inspector tool.

## UI Inspector CLI proposal

This doc is a proposal for a new version of Layout Inspector (UI
Inspector) which builds on-top of what we have learned over the years
and is designed to be used by an AI agent.

The MVP for such a tool would be a basic CLI that, given a device serial
number and app package, dumps the UI tree including views and
composables.

```bash
ui-inspector dump-ui --device 123 --package com.my.app
```

Both options are optional: `--device` defaults to the only online
device and `--package` defaults to the app currently in the foreground.

Example JSON output:

```json
{
  "roots": [
    {
      "type": "ViewNode",
      "id": 1,
      "className": "android.widget.FrameLayout",
      "bounds": { "x": 0, "y": 0, "width": 1080, "height": 2400 },
      "children": [
        {
          "type": "ComposeNode",
          "id": 2,
          "className": "androidx.compose.material3.Text",
          "bounds": { "x": 48, "y": 96, "width": 200, "height": 48 },
          "sourceLocation": { "filename": "MainActivity.kt", "lineNumber": 18 },
          "parameters": [
            { "name": "text", "value": "Hello World" }
          ]
        }
      ]
    }
  ]
}
```

Developing an MVP first will enable us to establish the tool's core
architecture and identify any unexpected challenges. Should this initial
phase prove successful, we can expand the CLI by incorporating
additional Layout Inspector features. Eventually, this could evolve into
a unified backend serving both the CLI and the Layout Inspector
interface within Android Studio.

## High level design

The CLI would be made of two main parts, the host and the agent. The
host is running on the user’s machine and the agent on the device.

### Host-agent communication

The agent acts as the server, when it launches it creates a socket bound
to `localabstract:ui_inspector_<pid>`.

The host runs the following command to map a local port on the user’s
machine to the device’s socket:

```bash
adb -s <device> forward tcp:<localHostPort> localabstract:ui_inspector_<pid>
```

Then connects to `<localHostPort>`.

The data exchanged over the socket is serialized using protocol buffers.

### Agent

The agent is structured into three distinct layers to bypass Android's
class loader restrictions. The design principle is a replica of what App
Inspection already does, slightly adapted to our use case.

#### JVMTI entry point (agent/native)

The agent uses JVMTI to inject all the other inspector classes into the
app. This is done by using `AddToBootstrapClassLoaderSearch()` to load
`lib_ui_inspector_service.jar` directly into the JVM. By doing this the
contents of `lib_ui_inspector_service.jar` will be available to the
bootstrap classloader.

Finally, using JNI, the native code locates the injected
`InspectorService.java` (which is part of `lib_ui_inspector_service.jar`)
and calls its static `initialize` method.

#### Service loader (agent/service)

The contents of `lib_ui_inspector_service.jar` act as a bridge between the
bootstrap
classloader and the app classloader. The bridge is necessary because in
order to have access to the application classes `view-inspector.jar` and
`compose-inspector.jar` need to be loaded with a class loader that
descends from the app class loader.

The main class of `lib_ui_inspector_service.jar` is `InspectorService`,
which:

* Receives the path to
  the payload JAR (passed from C++).
* Finds the app class loader.
* Creates a new `DexClassLoader` (as a child of the app class loader) to
  load the payload JAR (`lib_ui_inspector_payload.jar`).
* Finds `InspectorLauncher` in the new class loader, and invokes its
  entry point.

#### InspectorLauncher (agent/payload)

This class is the entry point of the payload. Loading this class and its
dependencies via a separate `DexClassLoader` is necessary to avoid
loading interfaces like `androidx.appinspection` into the Bootstrap
ClassLoader, preventing potential version conflicts if the app or other
tools (like Android Studio's App Inspection) also use those libraries in
the same process.

It is responsible for:

* Spawning a background coroutine/thread.
* Creating a `LocalServerSocket` bound to
  `localabstract:ui_inspector_<pid>`.
* Entering a loop to accept connections from the
  Host CLI.
* Processing commands and interacting with the app's UI hierarchy.
* Instantiating View and Compose inspectors (reused from Layout
  Inspector).

#### ViewInspector (agent/inspectors/view)

This is the actual view inspector. It’s part of the dex that was loaded
by `InspectorService`.

### Host

The host gets the device serial number and package name from the command
invocation.
It runs `adb devices` and `adb shell getprop` to find the device and
hardware characteristics of the device.

Selects the appropriate `.so` and payload `.jar` files matching the
target devices and pushes them directly to the Android staging directory
on the device (`/data/local/tmp`).

It then starts the injection sequence:

* Queries the app private data directory dynamically using `run-as <package> pwd`
  to support custom multi-user environments (e.g. `/data/user/10/`).
* When the `resolution-stack` facet is requested, enables view debugging for the
  inspected app via `settings put global debug_view_attributes_application_package
  <package>` (allowing the platform to expose attribute resolution stacks). The
  setting is read first and skipped if it already names the package or the global
  `debug_view_attributes` is already enabled; when actually flipped it is left set
  (changing it in either direction restarts the app's activities) and a stderr
  notice explains how to clear it.
* Runs `adb shell run-as <package> cp` to move files from
  `/data/local/tmp` to the app memory space.
* Uses `run-as <package>` to `chmod` all binary and jar files to read-only (`444`),
  since the Dalvik classloader prevents loading dynamic dalvik bytecode `.dex`
  extensions from a writable app-data location.
* Periodically polls `/proc/net/unix` on the device using a retry loop until the
  agent's abstract Unix socket appears, preventing host connection race conditions.
* Creates the adb tunnel and triggers payload injection via
  `adb shell cmd activity attach-agent <package> /data/data/<package>/lib_ui_inspector_agent.so=/data/data/<package>/lib_ui_inspector_service.jar;/data/data/<package>/lib_ui_inspector_payload.jar;<pid>`.

## Compose Inspector

Because the compose inspector jar is shipped with the compose library it
would be ideal to be able to reuse the existing jar, otherwise we
wouldn't be able to support older versions of compose.

For this reason `InspectorLauncher` needs to bundle the
`androidx.appinspection` interfaces and create its own implementation,
then invoke the inspector factory to get the app inspection compose
inspector.

### jar resolution

The host will accept a `--compose-inspector <compose-inspector.jar>`, so
callers can specify which compose inspector jar it should use. This will
be useful when the CLI is used from Android Studio or if a user wants to
use a specific jar, like a snapshot version.

* **From Android Studio:** the CLI could expose a `get-compose-version`
  command that would return the version of compose running in the app.
  Then Android Studio could use the build system (as we currently do) to
  resolve the jar before invoking commands in the CLI that require
  compose.

When the flag is not provided the host needs to handle the resolution of
the compose inspector jar:

* Upon a successful connection with the agent, the host sends a
  `GetVersionCommand`.
* The agent resolves the `androidx.compose.ui:ui` version:
    * First checks if compose is present, by using reflection to see
      if...
    * Then checks `META-INF/androidx.compose.ui_ui.version` to get the
      actual version number.
* Upon receiving the `GetVersionResponse` with the version number, the
  host connects to Google’s public Maven, downloads the `.aar` and
  unpacks the internal `inspector.jar`.
  * *Note:* To support Jetpack Compose's Kotlin Multiplatform (KMP) transition in
    version 1.5.0+, the host automatically resolves the artifact ID to `ui`
    for versions < 1.5.0, and to `ui-android` for versions >= 1.5.0.
* In case the user’s machine does not have access to public maven, the
  CLI will also provide a flag to specify an alternative maven repo.

The host should check local caches on the user's machine (like gradle
cache) and have its own cache where it can store the downloaded jars.

## UI Inspector daemon

This CLI tool could be invoked many times for the same instance of an
app. It would be a waste to re-inject the agent every time. For this
reason we should inject the agent once and keep it running, so future
commands can just re-connect to it.

Before running a command the host would:

* Discover the PID of the app.
* Run `adb forward tcp:<host_port> localabstract:ui_inspector_<PID>`.
* Attempt connection to `tcp:<host_port>`.
* Send `PING`.
* If `PONG`: warm hit.
* If connection failed or no `PONG`: run `attach-agent` from scratch.

To prevent resource leaks if a device is unplugged, the agent implements a
5-minute inactivity timeout. If no message is received within the timeout,
the agent is terminated and resources are cleaned up.

***

**⚠️ Implementation Note:** The host-side connection reuse and `PING`/`PONG`
warm-hit detection are **not yet implemented in the CLI host**. Currently, the
host runs the full file-push and `attach-agent` injection sequence on every
command execution.

However, the agent (device) side **fully supports persistence**: the server socket
runs in a persistent loop, caches active inspector instances, and handles
subsequent `attach-agent` calls gracefully (duplicate server threads exit cleanly after detecting socket collision via `"Address already in use"` checks).
***

## Retrieving view attributes and composables parameters

The dump is lean by default. Additional data is selected with `--include`,
taking one or more comma-separated facets (the option is also repeatable):

* `attributes`: view attributes and composable parameters.
* `resolution-stack`: attribute style/theme resolution traces (implies `attributes`).
* `semantics`: Compose accessibility and semantics properties.
* `system-composables`: system/framework Composable nodes.
* `all`: everything above.

*Note: The separate `get-params` command proposed in early designs is not implemented.*

## Change detection

The standalone CLI deliberately ships no change-detection mode. An
earlier `dump-ui --record` sampling mode (bounded-window recording with
per-frame diffs) was removed before the CLI-team pitch: the
change-record schema was the least settled part of the interface, and
`android layout` already has `--diff` semantics that any change
detection should be designed against, together with the CLI team, at
integration time.

## Retrieving recomposition counts and state reads

This is outside the scope of the MVP, but since the agent is persistent
on the device we should implement start and stop commands (for example
`start-observing-recompositions` / `stop-observing-recompositions`). These
remain unimplemented in the current version.

## Open questions

* Should the compose inspector and view inspector communicate on device,
  or going through the host as we currently do in Layout Inspector?
  Going through the host has the advantage that most of the command
  parsing and ui tree building logic would run there instead of on
  device. The obvious downside is that an extra round trip to the device
  would be necessary.
* Where should the code for the agent package live? Moving it to
  `androidx` has the advantage of having testing infrastructure.

## Future of Layout Inspector - integrating CLI code into Android Studio

If this works well in the future we could consider migrating the current
Layout Inspector UI to use the same backend as the CLI.

Studio would become the host and hold the socket connection.
