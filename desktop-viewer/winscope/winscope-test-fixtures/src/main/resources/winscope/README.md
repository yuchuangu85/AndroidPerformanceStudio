# Android 15 Winscope fixture

`android15-sanitized.perfetto-trace` is an 80 KiB synthetic Android SDK 35
Perfetto trace for deterministic Winscope parser and UI tests. It is not a
capture from a person or a physical device.

## Source and license

The trace combines the following upstream Perfetto parser fixtures at the
official v57.2 commit
[`da1d152cff27890903d158fe96751de3aab883cc`](https://android.googlesource.com/platform/external/perfetto/+/da1d152cff27890903d158fe96751de3aab883cc):

- `windowmanager.textproto`
- `surfaceflinger_layers.textproto`
- `surfaceflinger_transactions.textproto`
- `shell_transitions.textproto`
- `inputmethod_{clients,manager_service,service}.textproto`
- `viewcapture.textproto`
- `protolog.textproto`

An additional synthetic `LID_EVENTS` CUJ record and Android SDK 35 system-info
packet are included. The upstream material is Copyright The Android Open Source
Project and licensed under Apache License 2.0; see
`LICENSE-AOSP-APACHE-2.0.txt`.

## Sanitization

- Package/activity/view names were replaced with `com.example.fixture` values.
- ViewCapture text and content descriptions were removed.
- ProtoLog stack-trace intern data and references were removed.
- No `android.input.inputevent` packets, screenshots, recordings, device serial,
  credentials, email addresses, or other user data are present.
- Packet sequence IDs and clocks were normalized so independently sourced
  packets form one deterministic, error-free trace.

Real Input or screen-media evidence must never be committed. Tests that need
those trust-boundary cases must create temporary synthetic payloads and delete
them after the test.

SHA-256:
`f6e017acc7885aec8d3ac0c83f0c0b71836db5f2e74bb90ca38a622a45b37bc6`

Validated with the repository-pinned Trace Processor v57.2: all listed source
tables contain rows, ProtoLog has zero stack traces, Input has zero rows, and
`stats WHERE value > 0` contains no parser/import error.
