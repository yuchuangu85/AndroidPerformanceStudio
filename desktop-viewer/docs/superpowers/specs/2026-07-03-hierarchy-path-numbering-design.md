# Hierarchy Path Numbering Design

## Goal

Add a human-readable structural number to every HIERARCHY row and reuse the same number in FINDINGS so users can locate a reported node quickly.

## Numbering

- The root node is `1`.
- Child indexes are one-based and appended to the parent path.
- Examples: `1.1`, `1.2`, `1.2.1`.
- Numbers are recalculated for each snapshot from the current tree structure.
- Numbers are presentation-only and are not added to the protocol or persisted.

## Presentation

- HIERARCHY rows render the path number before the class label, for example `1.2  FrameLayout`.
- FINDINGS render the matching path in square brackets, for example `[1.2] 子节点过多 · ...`.
- A finding whose `nodeId` is absent from the current snapshot renders `[—]`.

## Data Flow

`InspectorPresenter` traverses the snapshot once to produce tree rows and a `nodeId -> path number` lookup. Finding row models resolve their node number through that lookup. Compose renders only the prepared presentation models, ensuring both panels use the same numbering source.

## Testing

- Verify root, sibling, and nested HIERARCHY numbers.
- Verify a finding resolves to the same number as its tree row.
- Verify an unknown finding node uses `—`.
- Run focused desktop presenter tests, then the full build and test suite.

## Non-goals

- Stable numbering across structurally different snapshots.
- Persisting numbers.
- Changing node IDs or protocol payloads.
