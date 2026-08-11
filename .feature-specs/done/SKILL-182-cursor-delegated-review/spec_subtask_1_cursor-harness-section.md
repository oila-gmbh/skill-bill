# SKILL-182 · Subtask 1 — Cursor harness section in the review-delegation contract

## Scope

`orchestration/review-delegation/PLAYBOOK.md` is the single authored source of
per-harness delegated launch mechanics. It has sections for GitHub Copilot CLI, Claude
Code, and OpenAI Codex, an explicit "Junie delegated review is intentionally
unsupported", and a shared rule that closes the list:

> If the current runtime is not documented below, stop and say delegated review is
> unsupported for delegated-required scopes.

Add a `## Cursor` section so Cursor stops falling through that rule, and reconcile the
one shared rule that has no Cursor implementation.

Launch mechanics to state, grounded in how Cursor actually works:

- Cursor subagents live as markdown-with-frontmatter under `~/.cursor/agents/` (user
  scope) and `.cursor/agents/` (project scope, which wins on a name conflict). Skill
  Bill already installs the specialist rubrics there via `NativeAgentProvider.Cursor`
  (`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/NativeAgentRendering.kt`),
  so the parent invokes installed agents by name rather than composing a rubric inline.
- A lane is launched by naming its subagent in the prompt (`/name` or an explicit
  "use the `<name>` subagent" instruction), one lane per routed stack-specific review
  skill or selected specialist pass — the same fan-out shape as every other harness.
- Parallel lanes are requested explicitly in a single instruction that names all
  selected lanes, so they launch simultaneously rather than one-at-a-time.
- Model: Cursor subagent frontmatter defaults to `model: inherit`. The shared rule
  "delegated specialists should use the same model as the parent thread by default"
  is already satisfied; the Cursor section must not add a model override.
- The installed native agent's embedded governed rubric is authoritative — do not tell
  a Cursor worker to read a sibling rubric sidecar. This matches the Claude and Codex
  sections.
- Each worker returns only its structured findings. The parent owns `import_review`
  and `triage_findings`.

Lane accounting is the substantive difference and the reason this is not a copy of the
Claude section. The Cursor CLI exposes no `Task`-style launch tool and returns no
launch handle, so the shared rule "Track delegated workers by the ids returned when
they are launched" cannot be satisfied literally. Define Cursor lane identity as the
pair the parent already owns before launch: the routed area and the assignment digest
recorded in the launch plan. The lane-accounting consequences must survive unchanged:

- Every launched lane is reconciled against the launch plan by that identity before the
  parent merges.
- A lane that cannot be launched, or that returns without a structured findings report,
  is a failed lane and is reported explicitly. It is never absorbed into the merged
  output as if it had been covered.
- No global listing or polling to discover workers.

Also state the Cursor negative paths explicitly, because Cursor's delegation is
description-driven and can silently decline:

- If the parent cannot confirm a lane actually ran as a subagent — no returned
  structured report attributable to that lane's identity — that lane is failed, not
  inline-covered. A parent that answers a lane's rubric in its own context has produced
  an inline review and must say so rather than reporting delegated coverage.
- If the Cursor harness cannot launch subagents at all (subagents unavailable, no
  installed agent matching a selected lane), stop and report that delegated review is
  required for this scope but unavailable here. Never downgrade to inline.

Edit the shared-rules block minimally: the id-tracking rule becomes "track delegated
workers by the identity the harness makes available — the returned launch id where the
harness provides one, otherwise the routed area and assignment digest from the launch
plan", so the shared rule and the Cursor section agree. Do not weaken the
no-global-listing rule.

Do not touch the Copilot, Claude, Codex, or Junie sections. Do not restate specialist
worker rules that live in `orchestration/review-orchestrator/specialist-contract.md`.

## Acceptance Criteria

1. `orchestration/review-delegation/PLAYBOOK.md` contains a `## Cursor` section placed
   alongside the other harness sections.
2. The Cursor section states lane launch by named installed subagent, one lane per
   routed review skill or specialist pass, parallel launch of all selected lanes in a
   single instruction, and no model override.
3. The Cursor section states that the installed native agent's embedded governed rubric
   is authoritative and that workers return only structured findings, with parent-owned
   telemetry.
4. Cursor lane identity is defined as the routed area plus the assignment digest from
   the launch plan, and no Cursor rule depends on a harness-returned launch id.
5. The shared worker-tracking rule is reworded to admit both id-based and
   plan-identity-based tracking, so it no longer contradicts the Cursor section, and
   the prohibition on global listing/polling is unchanged.
6. The Cursor section states that a lane with no attributable structured report is a
   failed lane, and that a parent answering a lane's rubric in its own context is inline
   review and must be reported as such.
7. The Cursor section states that an unavailable subagent mechanism stops and reports
   delegated review as unavailable, with no inline fallback.
8. The Copilot CLI, Claude Code, OpenAI Codex, and Junie sections are byte-identical to
   their pre-change content.
9. `scripts/validate_agent_configs` and `npx agnix --strict .` pass.

## Non-Goals

- Native-agent frontmatter rendering (subtask 2).
- Documentation under `docs/delegated-review/` (subtask 3).
- Promoting Cursor beyond experimental, or changing mode resolution so `auto` can reach
  `delegated`.
- Adding a Cursor-specific support pointer; `platform-packs/*/platform.yaml` already
  points every pack's `review-delegation.md` at this one playbook.

## Dependencies

None. This subtask is the contract change the other two support.

## Validation Strategy

- Confirm no automated gate pins the playbook's H2 section list before editing. The
  Python-era `REVIEW_DELEGATION_REQUIRED_SECTIONS` gate in `scripts/skill_repo_contracts.py`
  no longer exists after the Kotlin runtime migration; `scripts/validate_agent_configs`
  now shells into `:runtime-cli:run --args="validate-agent-configs"`. Grep the Kotlin
  sources for a delegation-section list and, if one exists, change it in the same commit
  as the playbook — the history note in `agent/history.md` records that these must move
  atomically or the validator fails mid-stream.
- Run `scripts/validate_agent_configs` and `npx agnix --strict .`.
- Diff the four untouched harness sections to prove they are unchanged.
- Re-read the shared-rules block end to end and confirm no rule now contradicts the
  Cursor section — specifically the id-tracking rule and the undocumented-runtime rule.

## Next Path

Subtask 2 fixes the Cursor native-agent frontmatter the launched lanes depend on.
