# Frame probe acceptance fixture

`Main.java` is a test Activity, **not** an optimization pattern. The intentional
24 ms sleep makes it possible to test a known, reviewable change without
sharing a user's source or relying on production devices.

From `rust-desktop/`, the ordinary `cargo test -p aps-app probe_to_verified_optimization`
test indexes a disposable copy, generates and applies the Trace diff, issues an
approved request to a one-shot loopback Responses fixture, validates citations,
applies the result only to the temporary project and checks source navigation,
ambiguity, revocation and stale-content rejection. No Android device is needed.

For the opt-in live check:

1. Use a disposable Android 12+ emulator. Build the file as the main Activity
   of a debuggable app with application ID `aps.test.frameverify`. For probe
   generation keep its source-workspace-relative path exactly `Main.java`,
   use this file unchanged and instrument `paint` with APS. Install and start
   the app, leaving the animation visible.
2. Provide a loopback Responses-compatible model named `aps-frame-smoke`
   (default port 12345). A fast or non-thinking profile is useful: an answer
   outside the normal provider deadline is not accepted. The test uses a
   fixture-only credential and never reads APS user settings.
3. Run:
   ```sh
   APS_FRAME_TEST_EMULATOR=emulator-5554 \
   APS_FRAME_TEST_PID="$(adb -s emulator-5554 shell pidof aps.test.frameverify | tr -d '\r')" \
   APS_FRAME_TEST_MODEL_PORT=12345 \
   cargo test -p aps-app live_probe_to_local_model -- --ignored --nocapture
   ```
   This uses the production recorder, pinned Trace Processor, snapshot/policy
   checks, approval/runtime and diff reviewer. No workspace is uploaded. The
   expected result for this synthetic case is one valid, cited edit; an empty
   finding is permissible product behavior but does not pass this smoke test.
   To measure a proposed change, review the printed patch and rebuild and
   recapture **the disposable app**, under the same conditions.

## Observed validation (2026-11-19)

On an isolated API 34 emulator, a three-second target capture yielded 119
FrameTimeline rows, 108 with the generated probe. The local Gemma model (LM
Studio, loopback, reasoning disabled for that request) returned a reviewable
deletion of just the artificial sleep. The deployed source matched the expected
instrumented hash; the patch passed `git apply --check`, was compiled and
reinstalled, and the same animation was recorded twice per group. The median
of per-run P95s changed from 43.05 to 25.69 ms; classified deadline misses
changed from 200/200 to 298/300. One after-change row had no measured duration
and did not enter the denominator. These are fixture observations, not a claim
of smooth rendering or a speedup on a physical device or production app.
