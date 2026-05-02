# Multi-runtime subagents for bill-kotlin-code-review and bill-feature-verify

- Issue key: SKILL-33 (overrides the TBD in the original file)
- Status: Complete
- Date: 2026-05-02
- Parent: SKILL-33 (Codex pilot) and follow-up 1 (OpenCode pilot)
- Sources:
  - SKILL-33 spec, non-goals: "Codex subagent support for `bill-kotlin-code-review`, `bill-feature-verify`, `bill-feature-implement`, or any other orchestrator skill -- separate follow-up issues."
  - The pilot established install primitive + authoring conventions for Codex (SKILL-33) and (after follow-up 1) for OpenCode. This issue spreads that pattern to the next two orchestrators.

## Background

`bill-kmp-code-review` was the SKILL-33 pilot because it has a small specialist surface (2 KMP-specific specialists). `bill-kotlin-code-review` is the larger sibling: it spawns 7+ specialist subagents (architecture, correctness, security, performance, testing, reliability, persistence, api-contracts, platform-correctness -- exact count to be confirmed at pre-planning). `bill-feature-verify` is review-shaped and reuses similar specialist delegation against the verify-specific contract.

Both orchestrators today degrade on Codex/OpenCode the same way the KMP review did before SKILL-33: serialized into the parent thread, no per-specialist context budget, no parallelism. This issue authors the missing TOML (Codex) and markdown (OpenCode) subagent definitions for both orchestrators and verifies the prose is runtime-neutral.

This issue depends on SKILL-33 (Codex install primitive) and follow-up 1 (OpenCode install primitive) -- both must be merged before this can ship.

## Acceptance criteria

1. `bill-kotlin-code-review` ships Codex TOML + OpenCode markdown subagent definitions for every specialist it delegates to. Files live under `platform-packs/kotlin/code-review/bill-kotlin-code-review/codex-agents/*.toml` and `.../opencode-agents/*.md`. Each file is valid for its runtime (required fields, name matches filename) and embeds the F-XXX Risk Register bullet contract inlined verbatim from `specialist-contract.md`.
2. `bill-kotlin-code-review`'s orchestrator content (`platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md`) is verified runtime-neutral re: spawn instructions; missing native-subagent runtime notes are appended in the same shape SKILL-33 used.
3. `bill-feature-verify` ships Codex TOML + OpenCode markdown subagent definitions for every specialist it delegates to (verify-specific specialists, distinct from review specialists). Files live under the analogous `codex-agents/` and `opencode-agents/` directories within the verify skill's repo location.
4. `bill-feature-verify`'s orchestrator content is verified runtime-neutral with subagent-runtime notes appended.
5. Specialist fan-out for both orchestrators is documented and chunked if it exceeds Codex's `max_threads = 6` (or OpenCode's documented limit). The Kotlin review with 7+ specialists almost certainly requires explicit chunking -- this AC is the gate that proves the prose handles it correctly.
6. Existing TOML and markdown validity tests (introduced in SKILL-33 / follow-up 1) automatically cover the new files via their manifest-driven walks. No new test scaffolding is required, but the assertions must continue to pass on the expanded set.
7. User-facing docs are updated to list the orchestrators that now have native-subagent coverage.
8. `agent/history.md` is updated per `bill-boundary-history` rules.

## Non-goals

- Codex/OpenCode subagent support for `bill-feature-implement` (follow-up 3).
- Bill-create-skill scaffolding (follow-up 4).
- Restructuring the specialist taxonomy or rewriting any specialist prompt logic. The TOML/markdown files are derived from existing `content.md` + `specialist-contract.md`; they should not introduce new review heuristics.
- Re-running the SKILL-33 install-primitive work -- that primitive is already in place.

## Open questions

1. Confirm exact count and names of `bill-kotlin-code-review`'s specialists at pre-planning. The repo currently has skill directories for: `bill-kotlin-code-review-architecture`, `-correctness` (or `-platform-correctness`), `-security`, `-performance`, `-testing`, `-reliability`, `-persistence`, `-api-contracts`. Pre-planning must enumerate the actual delegated set from `content.md`.
2. Does `bill-feature-verify` delegate to the same specialist set as `bill-kotlin-code-review`, or a verify-specific subset? Pre-planning must read the verify orchestrator's content and enumerate.
3. If a specialist already has a Codex TOML from SKILL-33 (the 2 KMP specialists), this issue must NOT duplicate it. Pre-planning verifies the specialist roster does not overlap.

## Consolidated spec

### Codex TOML authoring

For each specialist `bill-kotlin-code-review` delegates to, author a TOML file at the orchestrator-local `codex-agents/` directory. Required fields per SKILL-33: `name`, `description`, `developer_instructions`. The `developer_instructions` block sources the specialist's existing `content.md` and inlines the F-XXX contract from `specialist-contract.md` verbatim.

Repeat for `bill-feature-verify`'s specialists.

### OpenCode markdown authoring

For each specialist, author a markdown file at the orchestrator-local `opencode-agents/` directory. Filename = OpenCode agent name. Body = the same content as the Codex TOML's `developer_instructions`, with frontmatter only as required by OpenCode (resolved by follow-up 1's open question).

### Orchestrator prose verification

Read each orchestrator's `content.md`. If the runtime-neutral spawn language and native-subagent notes from SKILL-33 are missing, append them. Add the explicit fan-out chunking statement required by AC #5 if the specialist count exceeds the known concurrency ceiling.

### Tests

The validity tests added in SKILL-33 (`tests/test_codex_agents_toml.py`) and follow-up 1 (`tests/test_opencode_agents_md.py`) use manifest-driven walks (`platform-packs/**/codex-agents/*.toml`, etc.) and will automatically cover the new files. Verify they continue to pass after the new files are added. No new test files are required.

### Documentation

Add the two orchestrators to whichever docs page enumerates which orchestrators have native-subagent coverage on which runtimes. Keep the wording mechanical so the matrix is easy to update as more orchestrators ship.

### Boundary history

Append a single entry covering both orchestrators. Cite SKILL-33 and follow-up 1 as the install-primitive precedents and reference the parent install-primitive entry rather than duplicating its content.
