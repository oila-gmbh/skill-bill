---
issue_key: SKILL-25
feature_name: feature-implement-shell-pilot
feature_size: MEDIUM
status: Complete
created: 2026-04-21
depends_on: SKILL-21 (shell+content split — shipped), workflow contract pilot for bill-feature-implement (shipped)
---

# SKILL-25 — Pilot `bill-feature-implement` onto a shell + content split

## Problem

`bill-feature-implement` is now a real top-level workflow with durable state,
stable step ids, named artifacts, and CLI/MCP resume surfaces. But its skill
file still mixes two different concerns:

1. **Shell-owned workflow contract** — project overrides, workflow-state tools,
   continuation rules, telemetry ownership, artifact names, and the stable
   step graph.
2. **Author-owned execution body** — the procedural prompt that explains how
   to assess the spec, brief subagents, loop through review/audit, apply
   add-ons, and finish the PR flow.

That makes `skills/bill-feature-implement/SKILL.md` harder to maintain than
the other stable commands that already separate shell concerns from authored
content. It also leaves the workflow contract partially buried in a long skill
body instead of standing on its own as the authoritative orchestration layer.

## Why now

- The workflow runtime is now explicit and tested, so there is finally a
  stable shell-owned contract worth separating from the execution prose.
- The roadmap calls out reliability first and strengthening the existing
  stable commands before adding new surfaces.
- `bill-feature-implement` is the highest-leverage next candidate because it
  is already the first workflow pilot and carries the most orchestration
  detail.
- Keeping this step narrow avoids the bigger mistake: turning feature-workflow
  migration into another large framework project before the value is proven.

## Scope of this pilot

This pilot is intentionally narrow.

It applies only to the canonical top-level skill at:

- `skills/bill-feature-implement/`

It does **not** yet:

- flip `feature-implement` out of `PRE_SHELL_FAMILIES`
- change scaffolder output for downstream `bill-<platform>-feature-implement`
  overrides
- introduce pack-manifest declarations for feature-implement content
- migrate `bill-feature-verify`

The goal is to prove that separating shell from content makes the workflow
clearer and easier to validate without widening the contract surface again.

## Target shape

After SKILL-25, `skills/bill-feature-implement/` should look like:

```text
skills/bill-feature-implement/
  SKILL.md
  content.md
  reference.md
  shell-ceremony.md
  telemetry-contract.md
  <existing add-on supporting files / symlinks>
```

Ownership split:

- `SKILL.md` owns:
  - frontmatter
  - project overrides
  - workflow-state contract
  - continuation-mode contract
  - stable step ids
  - stable artifact names
  - telemetry lifecycle ownership
  - explicit pointer to `content.md`
- `content.md` owns:
  - phase-by-phase execution instructions
  - subagent briefing expectations
  - review/audit loop procedure
  - quality-check / PR-description handoff procedure
  - add-on discovery and add-on reading guidance
  - any human-authored prompt detail that is not part of the stable shell

## Acceptance criteria

1. `skills/bill-feature-implement/content.md` exists and contains the
   author-owned execution body that currently lives in `SKILL.md`.

2. `skills/bill-feature-implement/SKILL.md` becomes a shell-oriented file that
   keeps the workflow contract explicit and readable. It must retain:
   - frontmatter
   - project overrides
   - workflow-state section
   - continuation-mode section
   - stable step ids
   - stable workflow artifact names
   - telemetry ownership rules
   - a required execution pointer to `content.md`

3. The split preserves runtime behavior. This is a documentation/contract
   refactor, not a workflow-runtime rewrite. The existing
   `feature_implement_workflow_*` CLI, MCP, and persistence behavior stay
   unchanged.

4. The workflow shell remains the source of truth for the durable artifact
   contract. At minimum the shell must continue to name and describe:
   - `assessment`
   - `branch`
   - `preplan_digest`
   - `plan`
   - `implementation_summary`
   - `review_result`
   - `audit_report`
   - `validation_result`
   - `history_result`
   - `commit_push_result`
   - `pr_result`

5. The workflow shell remains the source of truth for continuation behavior.
   Resume semantics must stay documented in `SKILL.md`, not drift into
   `content.md` prose.

6. Existing supporting-file references remain valid:
   - `reference.md`
   - `shell-ceremony.md`
   - `telemetry-contract.md`
   - existing add-on supporting files or symlinks

7. Validator coverage is extended so `bill-feature-implement` loud-fails when
   the shell loses any of its required workflow markers or the new
   `content.md` sibling is missing.

8. Tests cover both acceptance and rejection paths for the new split:
   - valid `bill-feature-implement` shell with `content.md`
   - missing `content.md`
   - shell missing workflow-state section
   - shell missing continuation-mode section
   - shell missing the execution pointer to `content.md`

9. README and authoring docs are updated anywhere they currently imply that
   `bill-feature-implement` is still a single-file authored skill.

10. Validation still passes:
    - `.venv/bin/python3 -m unittest discover -s tests`
    - `npx --yes agnix --strict .`
    - `.venv/bin/python3 scripts/validate_agent_configs.py`

## Shell/content split rules

### What stays in `SKILL.md`

- Anything the runtime or validator should treat as contract.
- Anything a future resume/continue implementation depends on being stable.
- Anything that defines ownership boundaries between parent workflow and child
  skills.
- Anything that should remain short, inspectable, and durable across prompt
  edits.

### What moves to `content.md`

- Procedural instructions for how to perform each phase.
- Human-authored explanation of what to read and when.
- Subagent briefing detail.
- Review and audit loop detail.
- Add-on scanning and usage guidance.
- Any prompt copy that can evolve without changing the workflow contract.

### What should not happen

- Do not create a second shell contract for feature workflows.
- Do not move workflow artifact names into `content.md`.
- Do not move continuation semantics into `reference.md` only.
- Do not let `SKILL.md` become a full prompt again after the split.

## Non-goals

- Migrating `bill-feature-verify` in the same change.
- Flipping `feature-implement` out of `PRE_SHELL_FAMILIES`.
- Adding manifest-driven routing or pack-owned `feature-implement` content.
- Changing the workflow runtime schema, DB tables, CLI commands, or MCP tools.
- Rewriting the implementation methodology, subagent strategy, or audit logic.
- Introducing a generic workflow-shell abstraction shared by feature skills.

## Open questions to resolve in planning

1. Should `reference.md` stay as the detailed subagent contract source, with
   `content.md` linking to it, or should some of that material move into
   `content.md` directly? Recommendation: keep `reference.md` and let
   `content.md` reference it.

2. Should the validator treat the `content.md` pointer as a generic governed
   rule for workflow skills, or as a special-case rule for
   `bill-feature-implement` only? Recommendation: special-case it for
   `bill-feature-implement` in this pilot and generalize only after
   `bill-feature-verify` follows the same pattern.

3. Should `skill-bill show bill-feature-implement` prefer rendering
   `content.md`, `SKILL.md`, or both? Recommendation: keep the read path
   unchanged unless current tooling makes the split confusing in practice.

## Files expected to change

Created:

- `skills/bill-feature-implement/content.md`

Modified:

- `skills/bill-feature-implement/SKILL.md`
- `README.md`
- `scripts/validate_agent_configs.py`
- `tests/test_validate_agent_configs_e2e.py`
- any docs that describe the authored surface for `bill-feature-implement`

Possibly modified:

- `AGENTS.md`
- `docs/getting-started-for-teams.md`
- `skill_bill/show.py` or related read-surface helpers if the split exposes a
  bad authoring UX

Not modified:

- workflow DB schema
- workflow CLI/MCP transport surfaces
- `skills/bill-feature-verify/`
- `skill_bill/scaffold.py` family placement for `feature-implement`

## Feature flag

N/A. Contract and authoring-surface cleanup only.
