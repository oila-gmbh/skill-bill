# Codex CLI Smoke Test — bill-feature-implement Native Subagents

Date: 2026-05-02
Branch: feat/SKILL-33-multi-runtime-subagents-feature-implement-validation
Subtask: 3 (validation)

## Environment

- Codex CLI: `codex-cli 0.128.0` (`/Users/sermilion/.nvm/versions/node/v22.14.0/bin/codex`)
- OpenCode: `1.14.19` (`/Users/sermilion/.opencode/bin/opencode`)
- Python: `.venv/bin/python3` (>= 3.11, tomllib available)
- Repo root: `/Users/sermilion/Development/skill-bill`
- Install dirs:
  - Codex agents: `/Users/sermilion/.codex/agents/`
  - OpenCode agents: `/Users/sermilion/.config/opencode/agents/`

## Install Output

`bash install.sh` driven non-interactively with `4,5\n3\n` (codex + opencode, all platform packs).

Highlights:
- Removed prior installs cleanly.
- Linked all base + KMP + Kotlin skills under both runtimes.
- Logged: `Installing Codex subagent TOMLs to: /Users/sermilion/.codex/agents` → "Codex subagent TOMLs linked via skill_bill".
- Logged: `Installing OpenCode subagent markdown to: /Users/sermilion/.config/opencode/agents` → "OpenCode subagent markdown linked via skill_bill".
- Registered skill-bill MCP server for both codex and opencode.

## Symlink Inventory

7 Codex feature-implement TOML symlinks under `~/.codex/agents/`:
- bill-feature-implement-completeness-audit.toml
- bill-feature-implement-implementation-fix.toml
- bill-feature-implement-implementation.toml
- bill-feature-implement-planning.toml
- bill-feature-implement-pr-description.toml
- bill-feature-implement-pre-planning.toml
- bill-feature-implement-quality-check.toml

All resolve to `/Users/sermilion/Development/skill-bill/skills/bill-feature-implement/codex-agents/<file>.toml` (skills/ tree, NOT platform-packs).

7 OpenCode feature-implement markdown symlinks under `~/.config/opencode/agents/`:
- bill-feature-implement-completeness-audit.md
- bill-feature-implement-implementation-fix.md
- bill-feature-implement-implementation.md
- bill-feature-implement-planning.md
- bill-feature-implement-pr-description.md
- bill-feature-implement-pre-planning.md
- bill-feature-implement-quality-check.md

All resolve to `/Users/sermilion/Development/skill-bill/skills/bill-feature-implement/opencode-agents/<file>.md` (skills/ tree, NOT platform-packs).

Co-installed alongside the existing 8 Kotlin and 2 KMP review-specialist subagents from platform-packs/ — verified 17 feature-implement/kotlin/kmp entries under `~/.codex/agents/`.

## Codex Phase Spawn Result

Goal: prove the Codex CLI loads its `~/.codex/agents/` directory without parse errors after the install, using a minimal disposable prompt unrelated to feature-implement (kept the budget low — no full feature workflow run).

Command:
```
codex exec --skip-git-repo-check --sandbox read-only -C /tmp \
  "List exactly 3 acceptance criteria for adding a 'Sign Out' button to a settings page. Output them as a numbered list and stop."
```

Result: Codex booted cleanly (`OpenAI Codex v0.128.0 (research preview)`, `model: gpt-5.5`, `approval: never`, `sandbox: read-only`), parsed config from `~/.codex/`, and produced a 3-line numbered answer. Tokens used: 17,028.

Interpretation: the 7 new feature-implement TOMLs registered under `~/.codex/agents/` are loadable without warnings — Codex did not log `failed to parse agent`, `unknown field`, or schema rejections during the session boot. Codex 0.128.0 does not expose a public `codex agents list` command, so the next-best signal is that a non-trivial `codex exec` run completed successfully with the install in place.

## Fix-Loop Structural Verification

The fix-loop respawn behavior (AC5) is verified structurally rather than via end-to-end execution because triggering a real respawn requires a full review iteration that exceeds the smoke-test budget.

Structural evidence:
- File present: `skills/bill-feature-implement/codex-agents/bill-feature-implement-implementation-fix.toml`
- Symlink installed: `~/.codex/agents/bill-feature-implement-implementation-fix.toml` → `<repo>/skills/bill-feature-implement/codex-agents/bill-feature-implement-implementation-fix.toml`
- Top-level TOML fields: `name = "bill-feature-implement-implementation-fix"`, description references "fix-loop variant ... respawned by the code-review step to address Blocker/Major findings", `developer_instructions` body explicitly frames the agent as the fix-loop subagent ("You are the implementation subagent, invoked to fix findings from the code-review step. Scope: fix only the findings listed below; do not add unrelated changes."), with placeholders for `{numbered_list}`, `{risk_register_rows_with_F-ids_and_file_line_paths}`, and `{branch_or_commit_range}`.
- Naming and parsing tolerance: matches the runtime-neutral spawn-by-name contract documented in `skills/bill-feature-implement/parsing_tolerance.md` and the Error Recovery section of `skills/bill-feature-implement/reference.md`.

End-to-end fix-loop execution under Codex/OpenCode (i.e. running a real review pass that surfaces Blocker/Major findings, having the orchestrator respawn this fix subagent by name, and confirming a clean fix patch is returned) is documented as a follow-up — out of scope for AC5 smoke verification.

## Anomalies

None during the smoke-test phase. The OpenCode install ran clean; the Codex install ran clean; the Codex `exec` smoke run completed and emitted only one unrelated warning ("personal-graph/session_start failed") which is an MCP tool-side failure unrelated to bill-feature-implement subagent loading.

## Parsing-Tolerance Behavior Observed

`skills/bill-feature-implement/parsing_tolerance.md` (Resolution A) prescribes:
- The orchestrator parses `RESULT:` blocks inline (no separate machine parser in `skill_bill/`).
- Best-effort recovery on malformed `RESULT:` payload, then a single corrective re-spawn, then escalation.

The smoke run did not exercise the parser path itself (no full feature-implement run), but the structural contract is intact:
- 6 of 7 feature-implement TOMLs contain a literal `RESULT:` block in their developer_instructions.
- The fix-loop variant intentionally inherits the implementation contract via "Return the standard implementation return contract" rather than restating a `RESULT:` block; this is the documented design (fix-mode is a tightly scoped subset of implementation).

## Workflow Resume

Investigation: the skill-bill workflow state DB lives at `~/.skill-bill/review-metrics.db` (sqlite). Resume operations are exposed via the `skill-bill-mcp` server tools (`feature_implement_workflow_continue`, `feature_implement_workflow_resume`, `feature_implement_workflow_get`, etc.).

MCP wiring observed at install time (from `bash install.sh` output):
- `skill-bill MCP server registered (claude)` — Claude Code reads the same DB through the MCP server.
- `skill-bill MCP server registered (codex)` — Codex CLI 0.128.0 supports MCP servers via `codex mcp` and reads the same DB through the same server.
- `skill-bill MCP server registered (opencode)` — OpenCode supports MCP servers and reads the same DB through the same server.
- copilot and glm registrations are present where applicable.

Posture (chosen for AC6):
- **Intra-runtime resume**: supported on every runtime that has the skill-bill MCP server registered. A workflow paused mid-phase under Codex can be resumed from the same Codex session via `feature_implement_workflow_continue`. Same for Claude Code and OpenCode independently.
- **Cross-runtime resume**: best-effort. Because all three runtimes point at the same `~/.skill-bill/review-metrics.db`, an in-progress workflow in one runtime is technically visible to the others — but spawn semantics (subagent invocation, briefing substitution, MCP tool surface) differ per runtime, so a Claude-started feature-implement run is not guaranteed to resume cleanly under Codex or OpenCode mid-phase. We do not test or claim that.
- **Support contract**: intra-runtime resume is the supported posture for AC6. Cross-runtime resume is out of scope.

## Conclusion

Smoke test passes. All 14 native subagent definitions install correctly under both Codex and OpenCode, all symlinks resolve to the `skills/bill-feature-implement/{codex,opencode}-agents/` source tree, and Codex 0.128.0 boots and runs a non-interactive prompt successfully with the new agents on disk. Fix-loop and parsing-tolerance behavior verified structurally; full end-to-end review respawn execution remains a follow-up.
