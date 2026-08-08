# UI Inspector CLI - Agent Guide
* Ref: `tools/vendor/google/android/` (`run`/`interact` for ADB, `cli` for I/O).
* Style: Use imports instead of full class names.

## Running Bazel
Use wrapper: `tools/base/bazel/bazel test //tools/base/ui-inspector/...`

## Real Device Test (Automated Workflow)
To test against a real device without user intervention:

1. **Find device**: `adb devices` -> `<serial>`
2. **Find debuggable app**: `adb shell pm list packages -3` -> `<pkg>`
3. **Resolve activity**: `adb shell cmd package resolve-activity --brief <pkg>` (use last line)
4. **Force stop**: `adb shell am force-stop <pkg>` (mandatory on agent change)
5. **Start**: `adb shell am start -n <resolved_component_name>`
6. **Run CLI**: `tools/base/bazel/bazel run //tools/base/ui-inspector/host:cli -- dump-ui --package=<pkg> --serial=<serial>`
