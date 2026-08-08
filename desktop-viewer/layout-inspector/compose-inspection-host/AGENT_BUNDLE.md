# Compose agent bundle

Full runtime Compose inspection is deliberately unavailable unless APS is
packaged with its verified AOSP agent bundle. Build it from a clean Android
source checkout at the commit pinned in `third_party/aosp-ui-inspector`:

```bash
tools/build-compose-agent-bundle.sh /path/to/aosp /path/to/compose-agent-bundle
```

For a development build, enable the internal feature and point it at the output:

```text
-Dagentperf.compose.full.enabled=true
-Dagentperf.compose.agent.bundle=/path/to/compose-agent-bundle
```

The runtime never downloads or hot-updates agent code. Only the exact official
Compose `inspector.jar` matching the target library version may be resolved and
cached after explicit authorization.
