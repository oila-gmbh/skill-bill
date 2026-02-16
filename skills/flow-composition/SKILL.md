---
name: flow-composition
description: Compose multiple asynchronous streams into deterministic ViewModel UI state with explicit priority rules, no hidden fallbacks, and robust tests.
---

# ViewModel Flow Composition

Use this skill when a screen needs to combine multiple flows (e.g., sessions + user data + status) and state logic becomes confusing.

## Core rules
- Define source priority explicitly (primary vs enrichment streams).
- Keep transformations pure and deterministic.
- Avoid hidden fallback behavior; name each merge path clearly.
- Emit a complete sealed UI state (`Loading`, `Content`, `Error`, `Empty`).

## Composition pattern
1. Choose primary stream (drives existence/content).
2. Attach enrichment streams conditionally.
3. Convert to a single UI model in one combine/map pipeline.
4. Add one final defensive catch for transformation-level failures.

## Testing checklist
- Primary present + enrichment present.
- Primary present + enrichment missing.
- Primary missing -> expected empty/error state.
- One stream fails -> expected fallback state.
- State transitions are stable and ordered.
