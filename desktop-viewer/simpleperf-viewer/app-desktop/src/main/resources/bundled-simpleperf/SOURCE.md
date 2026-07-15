# Bundled Android simpleperf

These device-side executables come from Android NDK r27b (27.1.12297006),
under simpleperf/bin/android/<abi>/simpleperf.

The NDK is distributed under the licenses reproduced in NOTICE. The application
verifies each executable against the pinned SHA-256 digest before exposing it to
the ADB deployment path.

| Android ABI | NDK directory | SHA-256 |
| --- | --- | --- |
| arm64-v8a | arm64 | e814416ac315681bec1c28d73f0ddb2dd486ff49a1ad6cb28e49f387139bc18f |
| armeabi-v7a | arm | 39271a20f28e6304fb59bbc8fe560ee3dbbcf3ac0109cdbf8d2c08aa41006135 |
| x86 | x86 | 37ab52d87a815be407d7f758ff8ebe2060917215aec43775006b0a3697078baa |
| x86_64 | x86_64 | 830866314c3db85aa3ffcb7f72cd20b7225a0491e9cd71fdfedf1109604d7e89 |
