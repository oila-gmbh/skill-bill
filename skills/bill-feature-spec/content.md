---
name: bill-feature-spec
description: Prepare governed feature-spec artifacts from issue-keyed requirements, choosing single-spec or decomposed mode through the shared preparation path used by bill-feature-implement and bill-goal.
---

# Feature Spec Preparation

`bill-feature-spec` prepares governed feature-spec artifacts without starting implementation. It is the standalone preparation entry point used before `bill-feature-implement` or `bill-goal` execution.

## Intake Contract

Collect and confirm:

- issue key
- intended outcome
- acceptance criteria
- known constraints and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

## Mode Selection

Classify into one of two modes:

- `single_spec`: one normal implementation pass is appropriate
- `decomposed`: multiple independently resumable subtasks are required

Use `single_spec` by default unless the work clearly needs dependency-ordered subtasks.

## Shared Preparation Path

Always route preparation through the shared feature-spec preparation runtime path. Do not fork logic between `bill-feature-spec`, `bill-feature-implement`, and `bill-goal`.

The shared path is responsible for writing the governed artifacts and enforcing loud-fail validation behavior.

## single_spec Output Rules

For `single_spec`:

- write or update `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`
- set or preserve parent-spec status and acceptance criteria
- do not create `decomposition-manifest.yaml`
- loud-fail if a decomposition manifest already exists for the same issue directory

Return the next command as:

```bash
Run bill-feature-implement on .feature-specs/{ISSUE_KEY}-{feature-name}/spec.md
```

## decomposed Output Rules

For `decomposed`:

- write or update parent `spec.md`
- write two or more ordered `spec_subtask_*.md` files
- write or update `.feature-specs/{ISSUE_KEY}-{feature-name}/decomposition-manifest.yaml`
- validate the manifest against `orchestration/contracts/decomposition-manifest-schema.yaml`

Each subtask spec must contain scope, acceptance criteria, non-goals, dependency notes, validation strategy, and next path.

Return the next command as:

```bash
skill-bill goal <issue_key>
```

## Goal Runner Boundary

`skill-bill goal <issue_key>` is consumer-only. It must not synthesize specs from prose. If no decomposition manifest exists, it should continue to loud-fail and direct callers back to preparation.
