---
name: bill-feature-spec
description: Prepare governed feature-spec artifacts from issue-keyed requirements, choosing single-spec or decomposed mode through the shared preparation path used by bill-feature-task and bill-feature-goal.
---

# Feature Spec Preparation

`bill-feature-spec` prepares governed feature-spec artifacts without starting implementation. It is the standalone preparation entry point used before `bill-feature-task` or `bill-feature-goal` execution.

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

## Service / Spec Source Mode

`bill-feature-spec` accepts an optional `service:default/linear` argument that selects where
the prepared spec is tracked. This is orthogonal to `single_spec` vs `decomposed`: either mode
may run under either service.

Resolve the effective spec-source mode with the runtime, which encapsulates the precedence
`service: arg > config spec_type > local` and loud-fails on a malformed `.skill-bill/config.yaml`:

```bash
skill-bill config resolve-spec-type --arg <service-arg-or-empty>
```

- Pass the value after `service:` as `--arg` (for example `--arg linear` or `--arg local`).
  When no `service:` argument is given, or it is `service:default`, pass an empty `--arg` (or
  `--arg default`) so the command falls back to the repo-local `spec_type` and then to `local`.
- A `0` exit prints the resolved mode (`local` or `linear`) on stdout; use that as the
  effective mode.
- A non-zero exit means the config is malformed or the service value is unrecognized. Surface
  that failure and stop — do not guess a mode.

If the resolved mode is `linear`, follow **Linear Mode Preparation** before writing artifacts.
Otherwise follow **Local Mode**.

## Linear Mode Preparation

Run only when the resolved spec-source mode is `linear`. Execute these steps in this exact
order so that no Linear issue is created and no artifact is written unless the whole sequence
can proceed (no partial state):

1. **Verify the Linear MCP is available and authenticated.** If it is unavailable or
   unauthenticated, loud-fail with a clear message **before** creating any Linear issue or
   writing any artifact. Create nothing and write nothing on this path.
2. **Compose all spec content in memory first** — the parent spec, and for a decomposed
   feature each subtask spec. The `.feature-specs/{KEY}-{name}/` directory name is not known
   until the parent issue key is returned, so do not write to disk yet.
3. **Create the parent Linear issue**, tagged `task`, whose description is the parent spec
   content. Capture the returned issue key.
4. **Decomposed only:** create one sub-issue per subtask, each tagged `task`, each description
   carrying that subtask's spec content under a clear per-subtask header so the ticket is
   human-legible. Capture each sub-issue's `linear_issue_id`. Rehydration keys off
   `linear_issue_id`, not header text, so the header is for humans only.
5. **Adopt the parent issue's returned key** as the issue key, the
   `.feature-specs/{KEY}-{name}/` directory name, and the manifest `issue_key`.
6. **Write via the shared preparation path**, stamping `spec_source: linear` (the manifest
   top-level field for `decomposed`; the `spec.md` `spec_source: linear` line for
   `single_spec`) and recording each subtask's `linear_issue_id`. Do not fork `single_spec`
   vs `decomposed` writing logic beyond adding these fields.
7. **Mid-sequence failure (orphan parent):** if a sub-issue create fails after the parent
   issue already exists, loud-fail surfacing the created parent key and any created sub-issue
   keys for manual cleanup, and write no artifacts.

## Local Mode

Run when the resolved spec-source mode is `local` (no config, `spec_type: local`, or
`service:local`). Local mode is unchanged from today:

- Make no Linear calls.
- `spec_source` resolves to `local`.
- For `single_spec`, write **no** `spec_source` line — absence is read as `local`.

## Shared Preparation Path

Always route preparation through the shared feature-spec preparation runtime path. Do not fork logic between `bill-feature-spec`, `bill-feature-task`, and `bill-feature-goal`.

The shared path is responsible for writing the governed artifacts and enforcing loud-fail validation behavior.

## single_spec Output Rules

For `single_spec`:

- write or update `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`
- set or preserve parent-spec status and acceptance criteria
- do not create `decomposition-manifest.yaml`
- loud-fail if a decomposition manifest already exists for the same issue directory

Return the next command as:

```bash
Run bill-feature-task on .feature-specs/{ISSUE_KEY}-{feature-name}/spec.md
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
