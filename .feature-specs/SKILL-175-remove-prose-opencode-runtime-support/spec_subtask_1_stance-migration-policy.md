# SKILL-175 Subtask 1 - Stance, inventory lock, in-flight prose migration

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

Lock the product stance and define the careful cutover rules before any
destructive deletion. Prose was the original engine and shares bones with
runtime; OpenCode/zcode are being removed entirely (no install traces). This
subtask makes migration/quarantine policy and keep/delete heuristics
authoritative.

### Surfaces to produce / update

- `runtime-kotlin/agent/decisions.md` — supersede “opencode is prose-only /
  stays usable in prose” with: runtime-only engine; prose engine removed;
  OpenCode/zcode fully removed from the product (no refuse tier, no install
  target); future OpenCode support is a clean re-add.
- Parent inventory in `spec.md` treated as checklist; add any newly found
  `mode:prose` / `feature_task_prose` / `FeatureImplement` / `TASK_PROSE` /
  `opencode` / `zcode` product hits into the decision or a short checklist under
  `.feature-specs/SKILL-175-…/`.
- Explicit **in-flight prose row policy** documented in the decision (and later
  implemented in subtask 6): for existing `feature_task_workflows` /
  `feature_implement_sessions` / goal sessions with `mode=prose` choose one
  coherent policy:
  - **quarantine + loud-fail resume** with operator message to re-run under
    runtime, or
  - **one-shot migration** of compatible terminal/non-terminal rows,
  - never silent ignore and never half-execute through deleted MCP/CLI.
- Heuristic check-in: “remove product mode prose; keep English prose” examples
  listed in the decision so reviewers do not strip writing guidance.
- Heuristic check-in: OpenCode/zcode allowlist for greps = this feature-spec tree
  + `.feature-specs/done/**` only (no live product keep-list).

## Acceptance Criteria

1. A boundary decision records runtime-only feature execution, prose-engine
   removal, and **full OpenCode/zcode product removal** (no Plan A install keep,
   no permanent refuse-and-redirect tier).
2. An explicit in-flight prose workflow/session policy is written (quarantine
   loud-fail and/or migration) covering `feature_task_workflows.mode=prose`,
   `feature_implement_sessions`, and goal prose attribution — later subtasks
   must implement that same policy, not invent a different one.
3. The decision states that English “prose” / “governed prose” / review prose
   language is out of deletion scope, with at least three concrete keep
   examples.
4. Any additional removal surfaces found beyond the parent inventory are listed
   in the decision or SKILL-175 checklist before destructive deletion begins.
5. No product code or skill is deleted in this subtask beyond decision/docs
   needed for the stance (destructive cuts start at subtask 2+).

## Non-Goals

- Deleting skills, MCP tools, CLI commands, Kotlin prose branches, or agent
  enums (starts subtask 2+).
- Implementing OpenCode runtime support.

## Dependency Notes

- No subtask dependencies.
- Blocks all later subtasks: they must follow this stance and row policy.

## Validation Strategy

- Decision entry review against parent Decisions 1–6.
- Grep spot-check that the decision forbids “use prose” and forbids retaining
  OpenCode/zcode as install or refuse-tier agents.

## Next Path

```bash
skill-bill goal SKILL-175
```

After this subtask: proceed to subtask 2 (runtime-only callers + full
OpenCode/zcode purge).
