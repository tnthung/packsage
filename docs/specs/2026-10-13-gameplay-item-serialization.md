# Gameplay item serialization specification

Date: 2026-10-13

Status: approved implementation scope.

## Purpose

Keep automatic held-item context useful and bounded in representation while preserving the item's complete persistent merged component data. Java debug text for native component maps can expand registry-backed values into very large strings and is not a serialization contract.

## Requirements

- Retain the held item's registry ID, count, display name, and every persistent merged component value, including defaults.
- Encode the component map through Minecraft's registry-aware `DataComponentMap.CODEC` using the active level's registry serialization context. Keep registry-backed IDs and tags in their encoded form rather than Java `toString()` expansion.
- If the codec cannot encode the map, expose that metadata as unavailable/unknown. Do not fall back to debug text or silently omit the failure.
- Do not truncate actual item data or introduce a global request-size budget in this change.
- Preserve the existing gameplay context scope and screenshot behavior.

## Acceptance

A focused capture regression uses a representative stack with persistent components and a registry-backed tag/value. It verifies that item identity, count, display name, and encoded component values are retained in compact registry-aware form, and that the output does not use Java component-map debug text. Codec failure is explicit and never emits the previous expanded representation.
