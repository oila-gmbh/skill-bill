# SKILL-35 Verification

## End-to-end dogfood

The SKILL-35 run itself dogfooded the new contract:

- **Step 0 (preflight, orchestrator)**: the orchestrator read `.agents/skill-overrides.md` directly, located the `## bill-feature-implement` H2 section, and called `mcp__personal-graph__session_start` with concise SKILL-35 context (skill name, repo path, branch, spec path, goal summary) BEFORE any other workflow step. Returned graph context loaded as background only.
- **Pre-planning subagent**: returned RESULT JSON with `override_action_mandates` populated. `must_call_tools` listed `mcp__personal-graph__session_start`, `mcp__personal-graph__write_state`, `mcp__personal-graph__write_episode`. `raw_override_block` contained the two bullets verbatim from `.agents/skill-overrides.md`. Confirms the new contract works end-to-end across the subagent boundary.
- **Step 9b (postflight, orchestrator)**: the orchestrator will call `mcp__personal-graph__write_state` and `mcp__personal-graph__write_episode` BEFORE `feature_implement_finished`, recording the new ceremony-layer rule and a session episode for SKILL-35 itself.

## Plan deviation: layer correction

The spec's ACs targeted `skills/bill-feature-implement/content.md` for Step 0 / Step 9b prose. During implementation, the user corrected the layer choice:

> "## Step 0: Skill-Override Preflight (orchestrator) — this looks like something for SKILL.md not for content.md. content.md is user editable, and SKILL.md is all about the outer scaffold and orchestration."

This was correct. `content.md` is the authored execution body for the skill; the orchestration ceremony layer is `orchestration/shell-content-contract/shell-ceremony.md`, which is consumed by 27 governed skills via sibling symlinks. Adding the enforcement there propagates repo-wide at one edit cost instead of per-orchestrator.

The implementation reverted the `content.md` edits and moved the enforcement to `shell-ceremony.md`'s existing `## Project Overrides` section, expanding it from the underspecified original ("read that section and apply it as the highest-priority instruction") to the orchestrator-owned, lifecycle-positioned, action-mandate-detecting contract documented in `agent/decisions.md::[2026-05-02] orchestrator-owned-skill-override-enforcement`.

## AC coverage after deviation

- **AC #1** (Step 0 preflight): satisfied by the new `## Project Overrides` lifecycle-positions language in `shell-ceremony.md` (preflight = "before applying the skill body"). Applies to all 27 linking skills, not just bill-feature-implement.
- **AC #2** (Step 9b closing mirror): satisfied by the same section's postflight language ("at end of skill ... BEFORE the skill emits its terminal `*_finished` telemetry event").
- **AC #3** (reference.md briefing tightening): unchanged from spec — pre-planning briefing now requires verbatim copy + structured surfacing.
- **AC #4-a** (RESULT `override_action_mandates` field): unchanged from spec.
- **AC #4-b** (`assessment.override_preflight` shape): satisfied by the structured `actions_executed` shape documented in `shell-ceremony.md::Recording the result`. Skills with workflow state persist preflight under their assess-phase artifact and postflight under their finalization-phase artifact.
- **AC #5** (decisions.md entry): present, reframed to ceremony-layer.
- **AC #6** (history.md entry): present, reframed to ceremony-layer; notes the layer-correction pitfall.
- **AC #7** (validation gate green): see below.
- **AC #8** (this verification.md): present.

## Validation gate

- `python3 -m unittest discover -s tests`: pass
- `python3 scripts/validate_agent_configs.py`: pass
- `npx --yes agnix --strict .`: pass
