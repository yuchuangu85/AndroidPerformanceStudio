# Hierarchy Level-Index Numbering Design

## Goal

Replace long structural path numbers with compact `level-index` numbers so users can scan HIERARCHY rows and locate the corresponding FINDINGS entry more easily.

## Numbering

- The root node is `0-0`.
- The first value is the zero-based tree depth.
- The second value is a zero-based, global index among all nodes at that depth.
- Depth indexes are assigned in the existing preorder traversal order.
- Example:
  - root: `0-0`
  - root children: `1-0`, `1-1`
  - all grandchildren, regardless of parent: `2-0`, `2-1`, `2-2`
- The combination is unique within a snapshot because every node at a depth receives a different index.
- Numbers are recalculated from the current snapshot and remain presentation-only.

## Presentation

- HIERARCHY continues to render the number before the class label, for example `2-1  TextView`.
- FINDINGS continues to render the matching number in square brackets, for example `[2-1] 不可见节点 · ...`.
- A finding whose `nodeId` is absent from the current snapshot continues to render `[—]`.
- No spacing, typography, selection, or panel layout changes are included.

## Data Flow

`InspectorPresenter` maintains one next-index counter per depth while traversing the current tree. Each visited node receives `"$depth-$index"`, and the same value is stored in the existing `nodeId -> number` lookup used by FINDINGS. Compose continues to render only the prepared presentation models.

## Testing

- Verify the root is numbered `0-0`.
- Verify siblings at depth one are numbered `1-0`, `1-1`.
- Verify nodes under different parents share the depth-two sequence without duplicate numbers.
- Verify a finding resolves to exactly the same number as its HIERARCHY row.
- Verify an unknown finding node still uses `—`.
- Run focused desktop presenter tests, then the full project test and build checks.

## Non-goals

- Preserving the previous dotted path numbers.
- Encoding parent ancestry in the displayed number.
- Stable numbering across structurally different snapshots.
- Changing node IDs, analysis findings, protocol payloads, or persistence.
