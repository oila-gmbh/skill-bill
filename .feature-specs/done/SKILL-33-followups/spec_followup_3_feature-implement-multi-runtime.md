# Multi-runtime subagents for bill-feature-implement

- Issue key: SKILL-33
- Status: Complete
- Date: 2026-05-02
- Parent: SKILL-33 (install primitive) and follow-ups 1–2
- Implementation: PR #94 (foundation + authoring), PR #95 (validation)
- Verification: `.feature-specs/SKILL-33-multi-runtime-subagents-feature-implement/smoke_test_results.md`
- Sources:
  - SKILL-33 spec, non-goals: bill-feature-implement is named as "the hardest" — sequential phases and structured-return parsing are load-bearing.
  - bill-feature-implement reference contract in the user's Claude command directory — defines the per-phase briefing templates and `RESULT: { … }` JSON return contracts for pre-planning, planning, implementation, completeness audit, quality-check, and PR-description subagents.

## Background

`bill-feature-implement` is qualitatively different from the review orchestrators:

1. **Sequential phases.** Each subagent's output (`preplan_digest`, `plan`, `implementation_summary`, `audit_report`, `validation_result`, `pr_result`) becomes the next subagent's input. The orchestrator parses each return as JSON.
2. **Fix-on-review respawn.** When code review surfaces Blocker/Major findings, the orchestrator spawns the implementation subagent AGAIN with a fix-focused briefing keyed off the review findings.
3. **Workflow state persistence.** A durable workflow_id tracks which step is current; subagents can be re-entered via `feature_implement_workflow_continue`.
4. **Strong return-contract enforcement.** The orchestrator parses `RESULT: { … }` JSON deterministically. On Claude this is enforceable because the `Agent` tool returns clean text. On Codex/OpenCode it is best-effort because the runtime spawns subagents in natural language and the parent reads the child's final message as a thread.

This issue authors subagent definitions for each phase AND addresses whether the structured-return parsing remains reliable on Codex/OpenCode. It is the largest of the follow-ups and may need to decompose at planning.

## Acceptance criteria

1. Codex TOML + OpenCode markdown subagent definitions exist for each phase that runs as a subagent today: pre-planning, planning, implementation (including the fix-loop variant), completeness audit, quality-check, PR-description. Files live under `bill-feature-implement`'s skill directory (for example, a `codex-agents/*.toml` directory mirrored for OpenCode), or under a repo-side authoring location that the install primitive copies to the runtime's agents directory.
2. Each subagent definition embeds the corresponding briefing template from `reference.md` verbatim (acceptance-criteria placeholders, return contracts, execution rules) so the parent orchestrator can spawn the agent on any runtime by name with a substituted briefing.
3. The orchestrator content (`content.md` for the deployed skill) is verified to use runtime-neutral spawn language. Where Claude-specific verbiage exists (`Agent` tool, `subagent_type=`), it is rewritten to spawn-by-name.
4. The structured-return contract (`RESULT: { … }` JSON parsed deterministically) is documented as best-effort on Codex and OpenCode. Two acceptable resolutions, picked at planning:
   - **Resolution A — accept best-effort:** the orchestrator parses RESULT: as it does today; on parse failure it surfaces the failure to the user and offers retry. Document the limitation prominently.
   - **Resolution B — defensive parsing:** the orchestrator extracts JSON from the subagent's final message using a tolerant regex/strategy and falls back to user prompts when parsing fails. More code, more reliable.
   The chosen resolution is part of the planning artifact; both must be evaluated.
5. The fix-on-review respawn flow works on all three runtimes. On Codex/OpenCode this means the orchestrator can issue a second natural-language spawn of the same `name` with a different briefing. Validate at planning that this is supported (Codex docs confirm `agents.max_depth = 1` does not block sibling re-spawns within the same orchestrator wave).
6. Workflow state persistence (`feature_implement_workflow_open`, `_update`, `_continue`) continues to function unchanged. Workflow state is independent of telemetry and independent of the host runtime.
7. End-to-end smoke test on at least one runtime besides Claude (Codex preferred): drive a small SMALL/MEDIUM `bill-feature-implement` run through every phase and confirm the orchestrator parses each subagent's return correctly. The smoke test result is captured in the PR description.
8. `agent/history.md` is updated per `bill-boundary-history` rules.

## Non-goals

- Adding new phases to `bill-feature-implement` or rewriting the existing phases' logic.
- Bill-create-skill scaffolding for these definitions (follow-up 4).
- Replacing the JSON `RESULT:` contract with a different format. The contract stays; parsing tolerance is the only knob.
- Coverage for runtimes other than Claude, Codex, and OpenCode.

## Open questions

1. Where do `bill-feature-implement` subagent definitions physically live in the repo? The orchestrator skill is installed via the existing skills install path; today the briefing templates live in `reference.md` inside the skill directory, not as standalone subagent files. This issue introduces a new authoring location (e.g. `<skill-dir>/codex-agents/`, `<skill-dir>/opencode-agents/`) that the install primitive ports to each runtime's agents directory.
2. Do subagents on Codex/OpenCode reliably emit a clean final-message containing only the `RESULT: { … }` block, or do they often add narrative? Resolve via the AC #7 smoke test before deciding on Resolution A vs Resolution B.
3. Is decomposition needed at planning? With 6 phase subagents × 2 new runtimes + 1 prose verification + 1 smoke test + docs/history, this is plausibly LARGE under the bill-feature-implement size rules (> 15 atomic tasks). Pre-planning may decompose into "authoring (Codex)", "authoring (OpenCode)", "parsing tolerance", and "smoke test + docs" subtasks.

## Consolidated spec

### Subagent authoring location

Introduce `<skill-dir>/codex-agents/<phase>.toml` and `<skill-dir>/opencode-agents/<phase>.md` for each of: `preplan`, `plan`, `implement`, `implement-fix`, `audit`, `quality-check`, `pr-description`. The install primitive's manifest-driven discovery already walks `platform-packs/**/codex-agents/*.toml` (and the OpenCode equivalent); extend the discovery roots if needed so it also walks `<skill-dir>/codex-agents/*.toml` for skills that don't live under `platform-packs/`.

If extending the discovery roots is undesirable, an alternative is to keep all subagent files under a manifest path that the install primitive already knows about. The pre-planning subagent makes this decision.

### Briefing template embedding

Each subagent file's `developer_instructions` (Codex TOML) or body (OpenCode markdown) contains the corresponding `reference.md` template verbatim, with the placeholder syntax (e.g. `{feature_name}`, `{numbered_list_of_acceptance_criteria}`, `{plan_json}`) preserved. The orchestrator substitutes placeholders at spawn time by passing the substituted text as the spawn briefing — same as it does today on Claude.

### Parsing tolerance

The orchestrator already parses `RESULT: { … }` JSON. Document any planned change here as a single `parsing_tolerance.md` decision artifact under the skill directory. The default position is Resolution A (accept best-effort and surface parse failures to the user). Resolution B is justified only if AC #7's smoke test surfaces frequent parse failures.

### Smoke test

Pick a SMALL `bill-feature-implement` exercise (e.g. a docs-only change to AGENTS.md or a one-line fix in a test) and run it end-to-end on Codex. Capture: each subagent's return parses cleanly; the workflow state advances correctly; the PR is created. Failures reveal gaps that block this issue from shipping.

### Documentation

Update the `bill-feature-implement` SKILL.md / content.md description to state explicitly that the skill runs natively on Claude, Codex, and OpenCode (after this issue ships).
