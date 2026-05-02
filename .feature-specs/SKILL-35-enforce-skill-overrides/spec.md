# Enforce `.agents/skill-overrides.md` mandates in bill-feature-implement

- Issue key: SKILL-35
- Status: In Progress
- Date: 2026-05-02
- Sources:
  - User feedback during SKILL-34 retrospective on 2026-05-02: "looks like a miss, we need to make sure that skill-override is always respected." Triggered after `mcp__personal-graph__session_start` / `write_state` / `write_episode` mandates from `.agents/skill-overrides.md` (per-skill `## bill-feature-implement` section) were silently skipped across SKILL-33 follow-ups 2/3/4 and SKILL-34 because the pre-planning subagent abstracted them away in `standards_notes`.
  - Existing reference: `skills/bill-feature-implement/reference.md:200` already says "Read `CLAUDE.md`, `AGENTS.md`, and any `.agents/skill-overrides.md` section matching `bill-feature-implement` when present. Treat all standards as mandatory." — but this is delegated to the pre-planning subagent, which paraphrased away the action mandate.

## Background

`.agents/skill-overrides.md` carries per-skill, per-orchestrator mandates that target the orchestrator's own behaviour (e.g. "call `mcp__personal-graph__session_start` before applying the skill body" and "write durable outcomes via `write_state`/`write_episode` at the end"). The current `bill-feature-implement` workflow has two structural failure modes:

1. **Subagent abstraction loss.** The pre-planning subagent reads `.agents/skill-overrides.md` and summarises it into a free-form `standards_notes` string. Operational mandates (must-call tools, exact MCP tool names, lifecycle position) get paraphrased into a single sentence and frequently dropped from the orchestrator's effective context.
2. **Wrong addressee.** Several override mandates target the orchestrator, not the subagents. The orchestrator never reads the file directly today — it delegates the read to a subagent which structurally cannot fulfil an orchestrator-only mandate (the subagent runs after the lifecycle position the mandate refers to).

Concrete evidence of the gap: across four bill-feature-implement runs on 2026-05-02 (SKILL-33 FU2, FU3, FU4, SKILL-34), the per-skill `## bill-feature-implement` override in `.agents/skill-overrides.md` was never honoured. No `session_start` was called, no `write_state` / `write_episode` was emitted at finish. The mandate text exists; the enforcement path doesn't.

## Acceptance criteria

1. `skills/bill-feature-implement/content.md` gains a new orchestrator-owned step (Step 0 — Skill-Override Preflight) before Step 1 (assess). The step reads `.agents/skill-overrides.md`, locates the section whose H2 heading matches `## bill-feature-implement`, parses the mandates, and executes any orchestrator-targeted action mandates immediately (e.g. calling `mcp__personal-graph__session_start` with a concise message including the skill name, the user's request, and known repo / spec / PR / issue context). If the file does not exist, or the section is absent, the step records "no override applies" in the `assessment` artifact and proceeds. The step is non-skippable; running Step 1 without first running Step 0 is a workflow contract violation.
2. `skills/bill-feature-implement/content.md` gains a closing mirror at the end of Step 9 (PR description) / before Step 10 (finish): if the override section had any closing mandates (e.g. `write_state` / `write_episode`), the orchestrator executes them before calling `feature_implement_finished`. The closing step records what was written so the audit can verify.
3. `skills/bill-feature-implement/reference.md` pre-planning briefing rule changes from the current "Treat all standards as mandatory" wording to a tighter contract: when `.agents/skill-overrides.md` has a section for this skill, the subagent must (a) copy its bullets **verbatim** into the digest, and (b) surface any **action mandates** (must-call MCP tools, must-read files, must-write paths, lifecycle position) as a new dedicated `override_action_mandates` field in the `RESULT:` JSON shape, separate from the free-form `standards_notes`. The pre-planning return contract is updated accordingly.
4. The pre-planning RESULT contract documented in `skills/bill-feature-implement/reference.md` adds the new `override_action_mandates` field with shape: `{"must_call_tools": ["mcp_tool_name", ...], "must_read_files": ["path", ...], "must_write_paths": ["path or graph node", ...], "lifecycle_position_notes": "<concise — when each mandate fires>", "raw_override_block": "<verbatim copy of the matched section, empty string if none>"}`. The orchestrator persists this field in `preplan_digest`.
4. The workflow-state artifact schema for `assessment` is extended (additively, no breaking change) with an `override_preflight` sub-key: `{"file_present": bool, "section_found": bool, "section_heading": "<heading or empty>", "actions_executed": [{"tool": "<name>", "status": "ok|error", "summary": "<short>"}, ...]}`. This is what the audit verifies in AC #1.
5. `agent/decisions.md` gets a new entry capturing this contract decision so future skill authors know that **any per-skill override targeting orchestrator lifecycle (must-call tools at session_start, must-write at finish, etc.) MUST add the matching invocation to the orchestrator's `content.md`** and not rely on the pre-planning subagent to surface it. This decision applies repo-wide; it isn't bill-feature-implement-specific even though the failure surfaced there.
6. `agent/history.md` is updated per `bill-boundary-history` rules with a single entry citing the SKILL-34 retrospective as the source.
7. Validation gate passes: `python3 -m unittest discover -s tests`, `python3 scripts/validate_agent_configs.py`, and `npx --yes agnix --strict .` all return without regressions vs main.
8. The fix is verified end-to-end by running `bill-feature-implement` on a tiny disposable spec (or by spawning the pre-planning subagent against this very spec at planning time) and confirming that (a) `mcp__personal-graph__session_start` was called by the orchestrator before Step 1, (b) the pre-planning RESULT JSON contains the new `override_action_mandates` field with `must_call_tools` listing the personal-graph tools, and (c) the closing `write_state` / `write_episode` is invoked at Step 10. The verification is recorded in this spec's directory as `verification.md` (or similar) before merge.

## Non-goals

- Changing the prose of any other skill's override section in `.agents/skill-overrides.md`. The override file's content is the contract; this issue changes how `bill-feature-implement` enforces it, not what the file says.
- Adding override-enforcement to other orchestrator skills (`bill-feature-verify`, `bill-kotlin-code-review`, `bill-kmp-code-review`, etc.). Those skills have the same structural gap; follow-up issues should mirror this pattern. Out of scope here.
- Building a generic "skill-override interpreter" library or DSL. The Step 0 logic is hand-coded for `bill-feature-implement`; the same pattern can be copy-pasted to other orchestrators by future issues.
- Auto-detecting which override mandates target the orchestrator vs subagents. The override author is responsible for writing mandates clearly; the orchestrator parses bullets and matches against a small set of known MCP tool prefixes (`mcp__personal-graph__`, etc.).
- Backfilling personal-graph state for the four prior 2026-05-02 runs that missed the mandate. Already done manually as part of SKILL-34's retrospective.
- Adding a runtime-level enforcement check (e.g. failing the workflow if the orchestrator skipped Step 0). Step 0 is enforced by being written into the orchestrator content; subsequent runs that skip it would be skipping documented protocol. A test that statically asserts the new Step 0 / closing mirror text exists in `content.md` is sufficient.

## Open questions

1. Should the closing mandate (AC #2) fire **before** or **after** `feature_implement_finished`? Proposal: fire **before** so the durable state captures the just-completed work without needing the finished event id. Pre-planning can confirm.
2. What's the exact MCP tool surface for personal-graph today, and which writes are recommended at end-of-skill vs `staging/`? Pre-planning resolves by reading the `personal-graph` MCP tool descriptions and any prior `state/preferences` nodes. Default closing posture if uncertain: one `write_state` for any durable invariant the run uncovered, plus one `write_episode` for the decision/event itself, both linked.

## Consolidated spec

### `content.md` changes

Add a new section between the existing "Orchestrator vs Subagent Split" section and "Step 1: Collect Design Doc + Assess Size" titled "Step 0: Skill-Override Preflight (orchestrator)". The body:

- States the step id is `override_preflight` (workflow-state extension; does NOT add a new stable step id to the canonical 12-list — Step 0 is part of `assess`'s artifact, recorded under `assessment.override_preflight`).
- Tells the orchestrator to read `.agents/skill-overrides.md` directly (not via subagent).
- Tells the orchestrator to grep the file for `## bill-feature-implement` and read the bullets that follow until the next H2.
- Tells the orchestrator to detect action mandates by simple substring match on known tool prefixes (`mcp__personal-graph__`, `mcp__skill-bill__`, etc.) and on must-read patterns ("read `<path>`").
- For each detected `mcp__personal-graph__session_start` mandate, calls the tool with a concise message including this skill name, the user's request, and known repo/spec/PR/issue context.
- Records the result in the `assessment` artifact's new `override_preflight` sub-key.
- If the file or the section is absent, records that and proceeds without action.

Add a closing mirror inside Step 9 (or as a new Step 9b before Step 10) that re-reads the same override section, looks for closing mandates (`write_state`, `write_episode`, etc.), and executes them before `feature_implement_finished`. Records the result in the `pr_result` artifact's new `override_postflight` sub-key.

### `reference.md` changes

Pre-planning subagent briefing: the existing instruction "Read `CLAUDE.md`, `AGENTS.md`, and any `.agents/skill-overrides.md` section matching `bill-feature-implement` when present. Treat all standards as mandatory." is replaced with:

> Read `CLAUDE.md`, `AGENTS.md`, and `.agents/skill-overrides.md`. If `.agents/skill-overrides.md` has a section whose H2 heading matches `## bill-feature-implement`, you MUST:
> 1. Copy every bullet under that heading verbatim into the `override_action_mandates.raw_override_block` field of your `RESULT:` JSON. Do not summarise, do not paraphrase, do not drop punctuation.
> 2. Extract any **action mandates** (sentences that direct an agent to call a specific MCP tool, read a specific file, or write to a specific path/node) into the structured `override_action_mandates` sub-fields (`must_call_tools`, `must_read_files`, `must_write_paths`, `lifecycle_position_notes`).
> 3. The orchestrator (Step 0 / Step 9b) is responsible for executing orchestrator-targeted mandates. Your job is to surface them, not to execute them.

Update the pre-planning RESULT JSON shape (currently 11 fields) to include the new `override_action_mandates` object as field 12. The orchestrator persists this in `preplan_digest` for use by later phases.

### `agent/decisions.md` entry

Append a new entry titled `[2026-05-02] orchestrator-owned skill-override enforcement`. Body explains:

- Per-skill override sections in `.agents/skill-overrides.md` that target the orchestrator's own lifecycle (must-call tools at session start, must-write at finish, etc.) are the orchestrator's responsibility to execute.
- The pre-planning subagent's job is to **surface** override mandates (verbatim + structured), not to execute them.
- The orchestrator's `content.md` must contain a Step 0 preflight and a closing mirror that read the override file directly and execute orchestrator-targeted mandates.
- This decision is repo-wide and applies to every orchestrator skill, not just `bill-feature-implement`. Other orchestrators should mirror this pattern in follow-up issues.

### Tests

Add a static assertion test (`tests/test_feature_implement_skill_override_enforcement.py` or extend an existing test file) that:

- Reads `skills/bill-feature-implement/content.md` and asserts the file contains a section heading matching `## Step 0: Skill-Override Preflight` (or equivalent agreed spelling).
- Reads `skills/bill-feature-implement/reference.md` and asserts the pre-planning RESULT JSON shape documentation includes the field name `override_action_mandates`.
- Asserts `agent/decisions.md` contains the new decision entry's title.

This test is intentionally a static-string contract check, not a behaviour test — Step 0 is prose protocol, not code, so the contract is "the prose says the right thing." Behavioural verification is covered by AC #8's manual end-to-end check.

### Boundary history

Append a single entry citing the SKILL-34 retrospective on 2026-05-02 ("looks like a miss, we need to make sure that skill-override is always respected") as the source. Note that the four 2026-05-02 runs (SKILL-33 FU2/3/4 + SKILL-34) all skipped the personal-graph mandate; this issue closes the structural gap that allowed that to happen.

### Validation

Standard gate: `python3 -m unittest discover -s tests && python3 scripts/validate_agent_configs.py && npx --yes agnix --strict .`. No new test infrastructure beyond the static-string assertion test described above.
