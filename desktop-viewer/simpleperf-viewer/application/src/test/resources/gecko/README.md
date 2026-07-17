# Gecko Profile test fixture

`aosp-gecko-profile.json.gz` is generated from the repository's pinned
`test-fixtures/src/main/resources/simpleperf/aosp-perf.data` with Android NDK
27.1.12297006:

```bash
python3 gecko_profile_generator.py -i aosp-perf.data > aosp-gecko-profile.json
gzip -n -c aosp-gecko-profile.json > aosp-gecko-profile.json.gz
```

- Input SHA-256: `cb3066f4050d84d3e204a37ca4c479113b7623b663c17a3ee8cae5a85b8238bf`
- Fixture SHA-256: `2f91b7492c4a8cf4d41a7436645eb78cad23bb5e4038e25c6e612bed369ce908`

The fixture contains Gecko Profile schema version 24, four threads, and 2,409
samples. It is used to verify compatibility with the exact output format of
the Android NDK `gecko_profile_generator.py` workflow.
