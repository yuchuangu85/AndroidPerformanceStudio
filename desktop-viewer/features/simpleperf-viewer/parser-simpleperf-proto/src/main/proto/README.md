# Simpleperf protobuf schema provenance

- Source: AOSP `platform/system/extras/simpleperf/cmd_report_sample.proto`
- Immutable ref: `refs/tags/android-17.0.0_r1`
- SHA-256: `575d7e78b52c3ac8b8835972de8861eabac7dd6cbfef79c07972ea647ae0b8fc`
- License: Apache-2.0 (header retained in the schema)

The schema is compiled by the Gradle protobuf plugin. The surrounding `SIMPLEPERF` framing is intentionally parsed by project code rather than protobuf's whole-file API.
