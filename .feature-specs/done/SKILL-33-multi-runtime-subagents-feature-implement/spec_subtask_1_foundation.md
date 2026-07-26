# Subtask 1 — Foundation: discovery extension, runtime-neutral orchestrator, parsing tolerance decision

Parent spec: /Users/sermilion/Development/skill-bill/.feature-specs/SKILL-33-followups/spec_followup_3_feature-implement-multi-runtime.md

## Why this is first

The Codex/OpenCode discovery primitives currently walk only `platform-packs/<slug>/**/codex-agents/*.toml` and `platform-packs/<slug>/**/opencode-agents/*.md`. The bill-feature-implement skill lives at `skills/bill-feature-implement/`, which is OUTSIDE that walk root. If we author the 14 subagent files first, they will not be discovered or installed by either the Python primitive or the shell fallback. Foundation must land first so the authoring milestone can be validated by actually installing.

In addition, the orchestrator content currently contains one Claude-specific spawn instruction at `reference.md:180` (`Launch via the Agent tool with subagent_type: "general-purpose"`). The runtime-neutral rewrite is part of foundation because the authored subagents will mirror that language. The `parsing_tolerance.md` decision artifact is also foundation — it records the JSON `RESULT:` parsing posture that authored subagents and the orchestrator will rely on.

## Scope

1. Extend `skill_bill/install.py`:
   - `discover_codex_agent_tomls(...)` — accept (or otherwise walk) BOTH `platform-packs/` and `skills/` roots so `skills/bill-feature-implement/codex-agents/*.toml` is picked up. Mirror the existing manifest-driven `rglob` style.
   - `discover_opencode_agent_mds(...)` — same extension for `skills/bill-feature-implement/opencode-agents/*.md`.
   - Keep behavior backwards-compatible for existing platform-packs callers; do not break the Kotlin code-review pack discovery.
2. Extend `install.sh` shell fallbacks (lines ~124 and ~156) so the same two new roots are walked when the Python primitive is unavailable. The shell glob list must stay in lockstep with the Python rglob roots (SHELL_CONTRACT_VERSION 1.1).
3. Rewrite `skills/bill-feature-implement/reference.md:180` to runtime-neutral spawn-by-name language. Audit the rest of `reference.md` and `content.md` for any other Claude-specific verbiage (`Agent tool`, `subagent_type=`, `general-purpose`) and rewrite each occurrence to spawn-by-name phrasing. Do not change the briefing CONTENT — only the spawn instruction wording.
4. Author `skills/bill-feature-implement/parsing_tolerance.md` as a prose decision artifact. Record the choice between Resolution A (best-effort + surface failure with retry) and Resolution B (defensive parsing). Recommend Resolution A given the orchestrator parses inline (no machine parser exists). Document: which runtimes are best-effort, what the orchestrator does on a malformed `RESULT:` block, retry posture, escalation path.
5. Update `agent/history.md` per `bill-boundary-history` rules with a foundation entry covering: discovery roots extension, runtime-neutral spawn rewrite, parsing tolerance decision.

## Acceptance criteria

1. `discover_codex_agent_tomls` and `discover_opencode_agent_mds` walk both `platform-packs/` and `skills/` and return all matching files from both trees, with no duplicates.
2. `install.sh` shell fallback walks both `platform-packs/**/` and `skills/**/` for the two agent dir patterns.
3. `reference.md` and `content.md` contain zero references to `Agent tool`, `subagent_type=`, or `general-purpose`. All spawn instructions are runtime-neutral spawn-by-name language.
4. `skills/bill-feature-implement/parsing_tolerance.md` exists, records a chosen resolution (A or B) with rationale, and is referenced from `reference.md` (or `content.md`) in the failure-handling section.
5. Existing `tests/test_codex_agents_toml.py` and `tests/test_opencode_agents_md.py` still pass — discovery extension does not regress Kotlin code-review pack discovery.
6. `agent/history.md` has a new entry summarising the foundation changes.

## Non-goals

- Authoring any new TOML or MD subagent files. Those are subtask 2.
- Extending tests to cover new files. That is subtask 3.
- Smoke test on Codex. That is subtask 3.
- Replacing the JSON `RESULT:` contract. Out of scope for the whole feature.
- bill-create-skill scaffolding integration (separate follow-up).

## Dependencies

None. This is the foundation subtask.

## Validation strategy

```
.venv/bin/python3 -m unittest discover -s tests \
  && npx --yes agnix --strict . \
  && .venv/bin/python3 scripts/validate_agent_configs.py
```

Plus a manual sanity check: run the install primitive against this repo and confirm zero new files are linked yet (subagent files do not exist yet) but no errors are raised about missing roots.

## Recommended next prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-33-multi-runtime-subagents-feature-implement/spec_subtask_1_foundation.md`.
