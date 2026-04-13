# bill-feature-implement-agentic

- Issue key: SKILL-10
- Date: 2026-04-13
- Status: Complete
- Sources: inline user request via `/bill-feature-implement`

## Summary

Introduce an **experimental** variant of `bill-feature-implement` called `bill-feature-implement-agentic` that runs each heavy phase of the workflow inside its own `Agent` subagent. The goal is to reduce context pollution in the orchestrator thread: the orchestrator keeps only structured return values, while subagents do the reading, planning, and editing in their own ephemeral contexts.

The classic `bill-feature-implement` stays as-is and remains the default.

## Phase split (orchestrator vs subagent)

**Orchestrator (stays in main context)**:
- Step 1 intake and single-pass assessment (needs user interaction)
- Step 1b branch creation
- Step 5 code review (invokes `bill-code-review`, which already spawns specialist subagents internally — no extra nesting)
- Step 7 boundary history (invokes `bill-boundary-history` inline — small and git-auditable)
- Step 8 commit and push (git ops must stay visible)

**Subagent (runs via `Agent` tool, sequential, same worktree)**:
- Step 2 pre-planning (boundary history read, spec save, pattern discovery)
- Step 3 plan creation
- Step 4 implementation
- Step 6 completeness audit
- Step 6b quality check (via `bill-quality-check`)
- Step 9 PR description (via `bill-pr-description`)

No parallel subagents. No worktree isolation.

## Acceptance criteria

1. A new base skill `bill-feature-implement-agentic` exists under `skills/base/bill-feature-implement-agentic/` with its own `SKILL.md` (and a `reference.md` if needed to keep the workflow file readable).
2. The skill follows the base naming shape `bill-<capability>` and passes the repo validator's naming, frontmatter, project-overrides, and skill-overrides checks.
3. The skill's workflow documents exactly which steps run as subagents and which stay in the orchestrator, matching the phase split above.
4. Every subagent step includes an explicit **briefing template** — self-contained context so the subagent does not re-read the raw spec. Briefings include: acceptance criteria, non-goals, boundary-history digest (when available), plan (when applicable), spec path, issue key, feature-flag pattern, validation strategy, phase-specific instructions.
5. Every subagent step defines a **structured return contract** — explicit fields the subagent must return, so the orchestrator never has to re-parse prose.
6. The orchestration telemetry contract is preserved: subagents collect child `telemetry_payload` entries and return them to the orchestrator, which passes the combined list to `feature_implement_finished`. One workflow run produces one `skillbill_feature_implement_finished` event.
7. The skill's SKILL.md/reference.md text contains the literal `orchestrated=true` pass-through instruction so the validator's `validate_orchestrator_passthrough` check passes. The skill is registered in the validator's `ORCHESTRATOR_SKILLS` list so the check actually targets it.
8. README catalog is updated: total skill count incremented by one, Feature Lifecycle section count incremented, and a catalog row is added describing the new skill as the experimental subagent-based variant.
9. `bill-feature-implement-agentic` is invokable as `/bill-feature-implement-agentic` (the base skill name maps directly to a slash command via the installer's standard symlink path — no extra wiring needed as long as the directory/skill name are correct).
10. Validation passes:
    - `python3 -m unittest discover -s tests`
    - `npx --yes agnix --strict .`
    - `python3 scripts/validate_agent_configs.py`

## Non-goals

- Making `bill-feature-implement-agentic` the default or deprecating the classic skill.
- Parallel subagents.
- Wrapping or modifying `bill-code-review`, `bill-quality-check`, or `bill-pr-description` themselves.
- Changing the MCP telemetry event schema or adding new events.
- Worktree isolation for any subagent.
- Adding new platform overrides.

## Feature flag / rollout

N/A. Agent-facing skill; no runtime feature flag.

## Out of scope for this PR but captured for later

- A dedicated test that spawns a real subagent and verifies the briefing contract end-to-end. For now, the shape of the skill text and structured return contracts are the enforceable surface.
- Collapsing shared workflow text with the classic `bill-feature-implement` via a common include file.
