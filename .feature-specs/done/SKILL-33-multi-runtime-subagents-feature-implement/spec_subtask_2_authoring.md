# Subtask 2 — Authoring: Codex TOML and OpenCode markdown subagent files for each phase

Parent spec: /Users/sermilion/Development/skill-bill/.feature-specs/SKILL-33-followups/spec_followup_3_feature-implement-multi-runtime.md

## Why this is second

Foundation (subtask 1) extended discovery to walk `skills/bill-feature-implement/codex-agents/` and `skills/bill-feature-implement/opencode-agents/`, made orchestrator content runtime-neutral, and recorded the parsing tolerance decision. With those primitives in place, the authored files will be discovered and installed automatically.

This subtask is mechanical authoring with zero install or discovery changes. Each file inlines a briefing template from `reference.md` verbatim per the boundary history rule that native agents cannot follow symlinks.

## Scope

Author 14 subagent definition files mirroring the canonical Kotlin code-review prior art (`platform-packs/kotlin/code-review/bill-kotlin-code-review/codex-agents/*.toml` and `opencode-agents/*.md`).

### Codex TOML files (`skills/bill-feature-implement/codex-agents/*.toml`)

1. `bill-feature-implement-pre-planning.toml`
2. `bill-feature-implement-planning.toml`
3. `bill-feature-implement-implementation.toml`
4. `bill-feature-implement-implementation-fix.toml` (fix-loop variant per AC1)
5. `bill-feature-implement-completeness-audit.toml`
6. `bill-feature-implement-quality-check.toml`
7. `bill-feature-implement-pr-description.toml`

### OpenCode markdown files (`skills/bill-feature-implement/opencode-agents/*.md`)

8. `bill-feature-implement-pre-planning.md`
9. `bill-feature-implement-planning.md`
10. `bill-feature-implement-implementation.md`
11. `bill-feature-implement-implementation-fix.md`
12. `bill-feature-implement-completeness-audit.md`
13. `bill-feature-implement-quality-check.md`
14. `bill-feature-implement-pr-description.md`

### Per-file authoring rules

- Filename stem MUST exactly match the `name` field (Codex top-level `name`, OpenCode frontmatter `name`).
- Codex TOML: top-level `name`, `description`, multi-line `developer_instructions` containing the briefing verbatim from `reference.md`. Mirror the exact structure of `bill-kotlin-code-review-architecture.toml`.
- OpenCode MD: frontmatter with `name`, `description`, `mode: subagent`. Body contains the briefing verbatim. Mirror `bill-kotlin-code-review-architecture.md`.
- The fix-loop variant briefings mirror the implementation briefing but include the fix-loop framing (re-spawn under `agents.max_depth=1`, reads code-review findings, applies fixes, returns updated `RESULT:`). Use the wording the orchestrator already uses for the fix loop in `reference.md`.
- Do NOT use symlinks. Inline content verbatim.
- Acceptance-criteria placeholders, return contracts (`RESULT: { ... }` JSON), and execution rules from `reference.md` MUST appear verbatim in each file. The `description` field should be a one-line role statement; the body/`developer_instructions` carries the full briefing.
- No Claude-specific verbiage (`Agent tool`, `subagent_type`, `general-purpose`) in any authored file. Spawn language is spawn-by-name.

## Acceptance criteria

1. All 14 files exist at the listed paths with content matching their reference.md briefing verbatim.
2. Each filename stem equals its `name` field.
3. Codex TOML files validate via `.venv/bin/python3 scripts/validate_agent_configs.py`.
4. OpenCode markdown files have `mode: subagent` frontmatter.
5. No file contains Claude-only verbiage.
6. `agent/history.md` updated with an authoring entry.

## Non-goals

- Discovery / install primitive changes (subtask 1).
- Test extensions or smoke test (subtask 3).
- Modifying briefing CONTENT in `reference.md` — content is already canonical, only the spawn-instruction prose was rewritten in subtask 1.

## Dependencies

- Subtask 1 (foundation) MUST be merged. Without the discovery extension, install will not pick up the new files; even though authoring this subtask is technically possible without it, the validation needs install to work end-to-end.

## Validation strategy

```
.venv/bin/python3 -m unittest discover -s tests \
  && npx --yes agnix --strict . \
  && .venv/bin/python3 scripts/validate_agent_configs.py
```

Plus manual sanity check: run install primitive locally and confirm 14 new symlinks appear under `~/.codex/agents/` (7 TOML) and `~/.config/opencode/agents/` (7 MD).

## Recommended next prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-33-multi-runtime-subagents-feature-implement/spec_subtask_2_authoring.md`.
