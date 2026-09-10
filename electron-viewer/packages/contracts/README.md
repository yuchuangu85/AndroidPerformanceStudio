# @aps/contracts

Versioned JSON contracts shared by the Electron main and renderer processes.
Ported from `desktop-viewer/platform-core/profiler-contracts`.

## Entry points

- `@aps/contracts` - `StudioResult` / `StudioError` and the `CaptureArtifact` v1 schema.
- `@aps/contracts/node` - Node-only helpers (`sha256File`, device-identity salt and pseudonymizer).

## Wire compatibility

`encodeCaptureArtifact` / `decodeCaptureArtifact` mirror Kotlin `CaptureArtifactJson`:
`encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true`, class
discriminator `producerType`. Kotlin value classes (`ArtifactId`, `Sha256`,
`ClockDomain`, `CapabilityId`, ...) serialize inline as strings.

### 64-bit integers

Kotlin `Long` fields - `performedAtEpochMillis`, `timestampNanos`, and the clock
mapping bounds - are represented as **`bigint`** in TypeScript. Decoding recovers
the exact value from the JSON source literal, and encoding writes it back as an
unquoted integer, so nanosecond timestamps do not lose precision through
JavaScript's 2^53-safe `number`. `pid` and `contractVersion` stay `number` (Kotlin `Int`).

## Golden fixtures

`fixtures/*.json` is fixture-driven: drop JSON produced by the Kotlin
implementation into the directory and `src/fixtures.test.ts` checks it for
decode + canonical re-encode stability. The bundled fixture is authored here as a
seed until Kotlin-generated fixtures are exported from the existing test suite.

## Commands

```bash
pnpm --filter @aps/contracts typecheck
pnpm --filter @aps/contracts test
```
