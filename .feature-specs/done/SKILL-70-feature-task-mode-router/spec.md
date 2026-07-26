---
status: Complete
---

# SKILL-70 — feature-task mode router

Created: 2026-06-06
Issue key: SKILL-70
Mode: single_spec

## Intended Outcome

Restructure the `bill-feature-task` skill surface into three tiers:

- `bill-feature-task-prose` — the prose orchestrator (renamed from `bill-feature-task-legacy`, deprecation framing removed, promoted to first-class mode)
- `bill-feature-task-runtime` — the runtime-backed skill (renamed from the current `bill-feature-task`)
- `bill-feature-task` — a new router that accepts `mode:prose` (default) or `mode:runtime` and delegates to the appropriate skill

`bill-feature` routes through `bill-feature-task` as before; the prose default means no new `claude --print` subprocess cost for standard single-spec feature work.

## Motivation

Every Claude-driven phase in the feature-task runtime uses `claude --print` (one subprocess per phase). Anthropic imposes a $100 usage limit on this mode. The prose orchestrator runs entirely within the invoking agent session and carries no per-phase subprocess cost. Users should be able to choose, with prose as the default.

## Affected Files

- `skills/bill-feature-task-legacy/` → deleted; content moves to `skills/bill-feature-task-prose/`
- `skills/bill-feature-task/` → deleted; content moves to `skills/bill-feature-task-runtime/`
- `skills/bill-feature-task-prose/` → new directory (promoted legacy prose orchestrator)
- `skills/bill-feature-task-runtime/` → new directory (current runtime-backed skill)
- `skills/bill-feature-task/` → new router skill
- `skills/bill-feature/content.md` → update dispatch references

## Acceptance Criteria

1. `skills/bill-feature-task-prose/content.md` exists with the prose orchestration content from the former `bill-feature-task-legacy`, the deprecation warning block removed, and the frontmatter `name` and `description` updated to reflect a first-class prose mode.
2. `skills/bill-feature-task-prose/native-agents/agents.yaml` exists, moved from `bill-feature-task-legacy/native-agents/agents.yaml`, with no content changes.
3. `skills/bill-feature-task-runtime/content.md` exists with the content from the former `bill-feature-task`, and the frontmatter `name` and `description` updated to reflect the runtime-backed mode.
4. `skills/bill-feature-task-legacy/` directory is removed.
5. The original `skills/bill-feature-task/` directory is replaced by the new router skill.
6. The new `skills/bill-feature-task/content.md` router:
   - Accepts `mode:prose` (default when absent) or `mode:runtime` in args.
   - Confirms the issue key, spec path, agent, and selected mode in one confirmation gate.
   - Delegates to `bill-feature-task-prose` when mode is `prose` or unspecified.
   - Delegates to `bill-feature-task-runtime` when mode is `runtime`.
   - Forwards `--agent`, `--agent-override`, and `--phase-agent` identically in both paths.
   - Does not add a second confirmation gate on top of the delegated skill's own gate.
7. `skills/bill-feature/content.md` dispatch section:
   - Removes the "Do not invoke `bill-feature-task-legacy`" line.
   - Routes single-spec output through `bill-feature-task` (unchanged name), defaulting to prose mode.
   - Does not hardcode a mode modifier; lets the default prose behavior apply.
8. `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass after the changes.
9. After reinstall, `/bill-feature-task`, `/bill-feature-task-prose`, and `/bill-feature-task-runtime` are all accessible as distinct skills.

## Non-Goals

- No changes to `runtime-kotlin/` code or MCP tools.
- No changes to `bill-feature-goal` behavior.
- No mode selection added to `bill-feature-spec` output (its "Run bill-feature-task on ..." next-path stays valid as-is).
- No changes to telemetry contracts or workflow-state schemas.
- No changes to the prose orchestrator's internal step logic or workflow-state tooling.
- No changes to the runtime skill's internal CLI invocation logic.

## Constraints

- `bill-feature-task-prose/native-agents/agents.yaml` must move verbatim — do not alter subagent names or bodies.
- The router must not re-implement confirmation logic; it delegates immediately after one gate.
- The router confirmation gate must show the resolved mode so the user can correct it before delegating.

## Validation Strategy

- `skill-bill validate`
- `npx --yes agnix --strict .`
- `scripts/validate_agent_configs`
- Manual check: `/bill-feature-task`, `/bill-feature-task-prose`, and `/bill-feature-task-runtime` resolve after reinstall.
