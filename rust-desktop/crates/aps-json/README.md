# aps-json

Strict bounded JSON for independent Rust domains. Existing locked serde 1.0.229
and serde_json 1.0.151 are pinned exactly; no second JSON syntax implementation is
introduced. `Value`, `Map` and `Number` use standard serde_json interfaces.

`read` and `parse_slice` enforce a pre-decode hard byte cap. A custom recursive
deserialization seed enforces depth, total value nodes, string bytes and collection
limits, retaining arrays and booleans and rejecting duplicate keys. Duplicate
fields are never accepted as silent last-wins. `to_vec` checks structure and uses
a capped writer so escaped output cannot exceed the encoded-byte budget.

Inputs default to 64 MiB / 64 depth / 1 million value nodes / 1 MiB per string /
100,000 items per collection. Object keys count against string and collection
budgets; value nodes have a separate budget. String escape decoding is still
bounded by the complete input-byte cap before it reaches the visitor.

Hierarchy archives can explicitly use `parse_slice_with_extended_depth` with a
caller-specified JSON depth budget capped at 320. This path opts out of
serde_json's separate container recursion cap, but the same strict seed still
checks depth before every value and keeps all byte/node/string/collection and
duplicate-key checks. The ordinary `read`, `parse_slice` and `to_vec` limits
remain capped at 64; no existing caller is implicitly opted into deeper input.

Errors are sanitized and omit source text, arbitrary paths and data values.
These are resource bounds, not malicious-code isolation or schema validation;
each domain must validate its own fields and semantics.

Test sources are written but deliberately not executed until the complete rewrite
is implemented.
