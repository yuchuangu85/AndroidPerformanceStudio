# Firefox Flame Graph compatibility matrix

Baseline: Firefox Profiler commit `9dd90d380ee711f209c4dcd89beec244eb6d3654`.

Android Performance Studio intentionally differs only in:

1. Compose styling.
2. Android data terminology.

| Row | Coverage | Expected parity |
|---|---|---|
| mixed | Native and managed Android frames in one profile | Equivalent stack structure, search, selection, context actions, and details entry point |
| native | Shared-library frames | Native frames remain selectable, transformable, and resolvable to symbols/binaries |
| managed | ART/JVM frames | Managed frames use the managed implementation category and remain searchable |
| kernel | Kernel frames | Kernel frames survive projection and implementation filtering |
| recursive | Repeated callsites | Recursive stacks project without infinite expansion and can be collapsed |
| source-less | Binary exists without source | Details panel falls back to symbol/disassembly explanation instead of failing silently |
| million-sample | Large SQLite-backed profile | Projection is cancellable, latest-generation-wins, and viewport layout remains below frame budget |
| deep-stack | Deep call chains | SQLite stack loading and flame projection preserve frame order and remain queryable |

The checked fixture in `FirefoxFlameGraphFixtures` creates the first six rows directly. The P0 task
`./gradlew :test-fixtures:runFlameGraphPerformancePoc` records the million-sample and scroll/selection
performance evidence in `docs/poc-results/firefox-flame-graph-<platform>.json`.
