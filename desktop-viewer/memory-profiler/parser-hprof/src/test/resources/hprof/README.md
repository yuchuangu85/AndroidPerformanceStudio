# HPROF test fixture provenance

`android-converted-sample.hprof` is a 174-byte synthetic Android heap dump converted by the locally installed Android SDK Platform Tools:

```bash
$ANDROID_HOME/platform-tools/hprof-conv \
  /tmp/android-sample.hprof \
  /tmp/android-converted-sample-v2.hprof
```

The committed fixture is the converter output renamed to `android-converted-sample.hprof`.

SHA-256:

```text
7d6727771e253c5f9445fff0f2497074fd1de91ee4977beb06608165c156ed66
```

The sample contains one `com.example.ConvertedSample` instance with a 24-byte shallow size. Keep the fixture below the Phase 1 test-spec limit of 1 MB.
