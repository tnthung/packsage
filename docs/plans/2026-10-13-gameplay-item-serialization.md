# Gameplay item serialization implementation plan

**Goal:** Replace automatic held-item component debug text with the native registry-aware codec while retaining persistent merged metadata.

**Spec:** [Gameplay item serialization specification](../specs/2026-10-13-gameplay-item-serialization.md). The capture contract remains defined by [PackSage v1](../specs/2026-10-12-v1.md), which requires fresh held/targeted item context and its request/world scope.

## Scope

- Serialize `stack.getComponents()` with `DataComponentMap.CODEC` and the active level registry access.
- Preserve item ID, count and hover-name text; surface codec failures as unavailable component metadata without a debug-string fallback.
- Add a focused regression for representative persistent component data, registry-aware output and failure handling.

## Validation

- Review the output and failure cases against the acceptance criteria and the local `NativeSnapshot` registry-codec conventions.
- Run `ContextCaptureTest` in the integration checkout after the other owned changes are joined.
- Confirm the installed gameplay context is compact on the next live capture; unit checks do not establish live model latency.
