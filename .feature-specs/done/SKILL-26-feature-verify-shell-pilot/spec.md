---
issue_key: SKILL-26
feature_name: feature-verify-shell-pilot
feature_size: MEDIUM
status: Complete
created: 2026-04-21
depends_on: SKILL-25 (feature-implement shell pilot), feature-verify workflow contract pilot (shipped)
---

# SKILL-26 — Pilot `bill-feature-verify` onto a shell + content split

## Problem

`bill-feature-verify` is now a real top-level workflow with durable state,
stable step ids, named artifacts, and CLI/MCP resume surfaces. But its skill
file still mixes two different concerns:

1. **Shell-owned workflow contract** — project overrides, workflow-state tools,
   continuation rules, telemetry ownership, artifact names, and the stable
   verify step graph.
2. **Author-owned execution body** — the procedural prompt that explains how
   to collect inputs, extract criteria, gather the diff, run the optional
   feature-flag audit, invoke code review, audit completeness, and issue the
   final verdict.

That makes `skills/bill-feature-verify/SKILL.md` harder to maintain than the
other stable commands that already separate shell concerns from authored
content. It also leaves the workflow contract partially buried in a long skill
body instead of standing on its own as the authoritative orchestration layer.

## Why now

- SKILL-25 just piloted the same shell + content split on
  `bill-feature-implement`, so `bill-feature-verify` is now the right second
  data point before any workflow-shell generalization.
- The workflow runtime is already explicit and tested, so there is now a stable
  verify contract worth separating from the execution prose.
- `bill-feature-verify` is the only other pre-shell top-level workflow, so this
  is the narrowest follow-up that can validate whether the pattern is actually
  reusable.
- Keeping this step narrow avoids the wrong next move: generalizing the
  validator or scaffolder after only one workflow-shell pilot.

## Scope of this pilot

This pilot is intentionally narrow.

It applies only to the canonical top-level skill at:

- `skills/bill-feature-verify/`

It does **not** yet:

- flip `feature-verify` out of `PRE_SHELL_FAMILIES`
- change scaffolder output for downstream `bill-<platform>-feature-verify`
  overrides
- introduce pack-manifest declarations for feature-verify content
- generalize the workflow-shell validator rules across multiple workflow skills
- revisit `skill-bill show` or `skill-bill explain` unless the split exposes a
  concrete UX problem

The goal is to prove that separating shell from content makes the verify
workflow clearer and easier to validate without widening the contract surface
again.

## Target shape

After SKILL-26, `skills/bill-feature-verify/` should look like:

```text
skills/bill-feature-verify/
  SKILL.md
  content.md
  audit-rubrics.md
  shell-ceremony.md
  telemetry-contract.md
  <existing supporting files / symlinks>
```

Ownership split:

- `SKILL.md` owns:
  - frontmatter
  - project overrides
  - workflow-state section
  - continuation-mode section
  - stable step ids
  - stable workflow artifact names
  - telemetry lifecycle ownership
  - explicit pointer to `content.md`
- `content.md` owns:
  - phase-by-phase execution instructions
  - criteria extraction flow
  - diff gathering procedure
  - feature-flag audit gating rules
  - review/completeness/verdict execution procedure
  - any human-authored prompt detail that is not part of the stable shell

## Acceptance criteria

1. `skills/bill-feature-verify/content.md` exists and contains the
   author-owned execution body that currently lives in `SKILL.md`.

2. `skills/bill-feature-verify/SKILL.md` becomes a shell-oriented file that
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
   `feature_verify_workflow_*` CLI, MCP, and persistence behavior stay
   unchanged.

4. The workflow shell remains the source of truth for the durable artifact
   contract. At minimum the shell must continue to name and describe:
   - `input_context`
   - `criteria_summary`
   - `diff_summary`
   - `feature_flag_audit_result`
   - `review_result`
   - `completeness_audit_result`
   - `verdict_result`

5. The workflow shell remains the source of truth for continuation behavior.
   Resume semantics must stay documented in `SKILL.md`, not drift into
   `content.md` prose.

6. Existing supporting-file references remain valid:
   - `audit-rubrics.md`
   - `shell-ceremony.md`
   - `telemetry-contract.md`
   - existing supporting files or symlinks

7. Validator coverage is extended so `bill-feature-verify` loud-fails when the
   shell loses any of its required workflow markers or the new `content.md`
   sibling is missing.

8. Tests cover both acceptance and rejection paths for the new split:
   - valid `bill-feature-verify` shell with `content.md`
   - missing `content.md`
   - shell missing workflow-state section
   - shell missing continuation-mode section
   - shell missing the execution pointer to `content.md`

9. README and authoring docs are updated anywhere they currently imply that
   `bill-feature-verify` is still a single-file authored skill.

10. Validation still passes:
    - `.venv/bin/python3 -m unittest discover -s tests`
    - `npx --yes agnix --strict .`
    - `.venv/bin/python3 scripts/validate_agent_configs.py`

## Shell/content split rules

### What stays in `SKILL.md`

- Anything the runtime or validator should treat as contract.
- Anything a future resume/continue implementation depends on being stable.
- Anything that defines ownership boundaries between the verify workflow and
  child skills such as `bill-code-review`.
- Anything that should remain short, inspectable, and durable across prompt
  edits.

### What moves to `content.md`

- Procedural instructions for how to perform each phase.
- Human-authored explanation of what to read and when.
- Criteria extraction and diff-gathering detail.
- Feature-flag audit, code review, completeness audit, and verdict procedure.
- Any prompt copy that can evolve without changing the workflow contract.

### What should not happen

- Do not create a second shell contract for verify workflows.
- Do not move workflow artifact names into `content.md`.
- Do not move continuation semantics into `audit-rubrics.md` only.
- Do not let `SKILL.md` become a full prompt again after the split.

## Non-goals

- Generalizing a reusable workflow-shell abstraction immediately after this
  pilot.
- Flipping `feature-verify` out of `PRE_SHELL_FAMILIES`.
- Adding manifest-driven routing or pack-owned `feature-verify` content.
- Changing the workflow runtime schema, DB tables, CLI commands, or MCP tools.
- Rewriting the verification methodology, review strategy, or verdict logic.
- Changing the nested parent-owned review telemetry model.

## Open questions to resolve in planning

1. Should `audit-rubrics.md` stay as the detailed audit/verdict source, with
   `content.md` linking to it, or should some of that material move into
   `content.md` directly? Recommendation: keep `audit-rubrics.md` and let
   `content.md` reference it.

2. Should the validator treat the `content.md` pointer as a generic
   workflow-skill rule after SKILL-25 and SKILL-26, or stay special-cased in
   this change too? Recommendation: keep the rule explicit for
   `bill-feature-verify` in this pilot, then generalize in a follow-up only if
   both workflow shells settle on the same contract shape.

3. Should `skill-bill show bill-feature-verify` prefer rendering `content.md`,
   `SKILL.md`, or both? Recommendation: keep the read path unchanged unless the
   split exposes a concrete UX problem in practice.

## Files expected to change

Created:

- `skills/bill-feature-verify/content.md`

Modified:

- `skills/bill-feature-verify/SKILL.md`
- `README.md`
- `orchestration/workflow-contract/PLAYBOOK.md`
- `scripts/validate_agent_configs.py`
- `tests/test_validate_agent_configs_e2e.py`
- `tests/test_feature_verify_workflow_contract.py`
- any docs that describe the authored surface for `bill-feature-verify`

Possibly modified:

- `AGENTS.md`
- `docs/getting-started-for-teams.md`
- `skill_bill/show.py` or related read-surface helpers if the split exposes a
  bad authoring UX

Not modified:

- workflow DB schema
- workflow CLI/MCP transport surfaces
- `skills/bill-feature-implement/`
- `skill_bill/scaffold.py` family placement for `feature-verify`

## Feature flag

N/A. Contract and authoring-surface cleanup only.
