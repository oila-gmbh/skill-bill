# Subtask 7 — residual-surface worklist (classified inventory)

Issue key: SKILL-175
Date: 2026-08-09

This is the completion evidence for subtask-7 task-1 (the residual-surface
worklist) and the reference the later tasks reconcile against. It is
reproducible: every command below is the verbatim inventory grep, run from the
repo root.

## Inventory greps (verbatim)

Allowlist trees excluded at the source (per parent spec section H and
removal-surface-checklist): `.feature-specs/**` (this SKILL-175 tree plus the
`.feature-specs/done/**` archives) and the `agent/history.md` /
`agent/decisions.md` decision records (historical, never rewritten — checklist
row 58).

```bash
# prose-engine tokens
grep -rniE 'mode:prose|mode:runtime|bill-feature-task-prose|feature_task_prose_|\
  goal_prose_|FEATURE_TASK_PROSE|TASK_PROSE|feature-task-prose|FeatureImplement|\
  feature_implement|WorkflowFamily\.IMPLEMENT' . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
  --exclude-dir=node_modules

# opencode/zcode product tokens
grep -rniE '\bopencode\b|\bzcode\b|McpOpenCode|McpZcode|OPENCODE|ZCODE' . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
  --exclude-dir=node_modules
```

## Classification result

Every live-tree hit is classified into one of:

- **(a) must-remove product surface** — none remain. The product code, scripts,
  skills, schemas, and product docs carry no live prose-engine or OpenCode/zcode
  surface after subtasks 1–6. The two scaffold render snapshots that subtask 7
  inherited as open (`runtime-infra-fs/src/test/resources/snapshots/scaffold/
  bill-{kmp,kotlin}-code-review.render.txt`) were already regenerated clean by
  an earlier subtask and no longer match the opencode/zcode greps.
- **(b) protected English “prose”** — natural-language use (AGENTS.md “active
  prose”, review/PR “plain prose”, schema “prose summary”, “governed prose”,
  JSON-vs-prose repair wording). Not matched by the prose-*engine* tokens above,
  so it never enters the guard set.
- **(c) quarantine / migration surface that must name the legacy token** — the
  retained read-only `feature_implement_sessions` / `FEATURE_TASK_PROSE` /
  `FeatureImplement*` wire and persistence surface (parent AC-5, subtask 6),
  the quarantine and negative-assertion tests, and the retirement/historical
  docs that document the retired prose event names passing through the
  Cloudflare proxy unchanged.

The full path-level classification is encoded, exactly, in the
`SKILL_175_ALLOWLIST` constant of
`runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/
RuntimeArchitectureTest.kt` (subtask-7 task-6 guard). The guard scans the whole
live tree and fails on any hit outside that explicit allowlist, so this worklist
is enforced rather than asserted.

## Verification (task-1 re-run, post-sweep)

The guard’s own scan (62 files carrying a banned token across the live tree,
every one an allowlisted quarantine/retirement path) was cross-checked in both
directions:

- every path the greps return is present in `SKILL_175_ALLOWLIST`;
- every `SKILL_175_ALLOWLIST` entry is actually matched by the greps (none
  spurious).

Zero unclassified hits remain outside `.feature-specs/**`, the decision/history
records, and the explicit allowlist.
