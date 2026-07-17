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

When preparing decomposed work, keep each subtask small enough to complete within one normal
implementation pass plus the bounded audit remediation loop. A subtask must own a coherent slice of
implementation and validation; do not create a catch-all final subtask whose scope is "full
validation", "remaining audit gaps", "navigator and full validation", or the parent feature's entire
test matrix. Distribute acceptance criteria and validation obligations to the subtasks that implement
the behavior. A final integration subtask is allowed only when it owns a narrow integration seam it
changes; it must not re-own broad parent criteria already assigned to earlier subtasks. The label
`MEDIUM` does not make a multi-boundary or full-matrix subtask acceptable.

If invoked after an `audit_gap_respec_suggested` block, treat the blocked subtask spec as the new
parent input. Use the blocked workflow id, latest audit output, and unmet criteria as evidence for
why re-spec is needed, then decompose only the unresolved scope into smaller ordered subtasks. Preserve
already-satisfied behavior as dependency context, not as new acceptance criteria to reimplement.

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

Always route preparation through the shared feature-spec preparation path. Do not fork logic between `bill-feature-spec`, `bill-feature-task`, and `bill-feature-goal`.

The agent writes all governed artifacts directly: parent `spec.md`, ordered `spec_subtask_*.md` files, and — for decomposed features — `decomposition-manifest.yaml` using the template in the decomposed Output Rules section above. No CLI call or MCP tool routes the manifest write; the agent fills the template from the planning subagent's decomposition RESULT and writes the file to disk. Schema validation happens when the runtime first reads the manifest, not at write time.

## Spec Format Contract

Every parent and subtask spec is read back by the runtime
(`FileSystemFeatureTaskRuntimeRunInvariantsSource`) to extract its acceptance
criteria. That reader is format-sensitive, so authored specs MUST follow this
contract or the runtime fails the run with "must list at least one criterion":

- The acceptance-criteria section heading MUST begin with `## Acceptance Criteria`
  (case-insensitive). A trailing qualifier such as `## Acceptance Criteria (this subtask)`
  is allowed; a different heading word is not.
- Each criterion MUST be its own list item: a numbered item (`1. ...`) or a
  bullet (`- ...`, optionally a `- [ ]` checkbox). Do not place criteria in
  prose paragraphs under the heading.
- At least one criterion is required in every spec the runtime will execute.

Prefer the canonical numbered form the runtime writer emits:

```markdown
## Acceptance Criteria

1. First criterion.
2. Second criterion.
```

## single_spec Output Rules

For `single_spec`:

- write or update `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`
- set or preserve parent-spec status and acceptance criteria
- do not create `decomposition-manifest.yaml`
- loud-fail if a decomposition manifest already exists for the same issue directory

Return the next command as:

```bash
Run bill-feature on .feature-specs/{ISSUE_KEY}-{feature-name}/spec.md
```

## decomposed Output Rules

For `decomposed`:

- write or update parent `spec.md`
- write two or more ordered `spec_subtask_*.md` files
- write or update `.feature-specs/{ISSUE_KEY}-{feature-name}/decomposition-manifest.yaml`

Write the manifest file directly from the template below. Fill every placeholder with values from the planning subagent's decomposition RESULT; do not leave any placeholder literal in the written file.

```yaml
contract_version: "0.4"
issue_key: "ISSUE-KEY"          # string, required
feature_name: "feature-name"    # string, required
parent_spec_path: ".feature-specs/ISSUE-KEY-feature-name/spec.md"  # string, required
spec_source: local               # local | linear; omit for local (runtime default)
execution_model: same_branch_commit_per_subtask  # same_branch_commit_per_subtask | stacked_branches
base_branch: main                # string, required
feature_branch: feat/ISSUE-KEY-feature-name  # string|null; non-null for same_branch_commit_per_subtask
stack_branches: []               # array; empty for same_branch_commit_per_subtask
current_subtask_intent:
  subtask_id: 1                  # integer >= 0; 0 when no subtask is active (none or complete)
  action: start                  # none (initial, nothing started) | start | resume | blocked | complete (all done)
subtasks:
  - id: 1                        # integer >= 1
    name: "Subtask name"         # string, required
    spec_path: ".feature-specs/ISSUE-KEY-feature-name/spec_subtask_1_slug.md"  # string, required
    status: pending              # pending | in_progress | blocked | complete | skipped
    branch: null                 # string|null; null until the runtime assigns one
    commit_sha: null             # string|null; null until the subtask commits
    workflow_id: null            # string|null; null until the runtime opens a workflow
    blocked_reason: null         # string|null; null unless status is blocked
    last_resumable_step: null    # string|null; null unless the subtask was interrupted
    linear_issue_id: null        # string|null; null for local spec_source
    dependencies: []             # array of {subtask_id, optional, skipped}
  - id: 2
    name: "Subtask name"
    spec_path: ".feature-specs/ISSUE-KEY-feature-name/spec_subtask_2_slug.md"
    status: pending
    branch: null
    commit_sha: null
    workflow_id: null
    blocked_reason: null
    last_resumable_step: null
    linear_issue_id: null
    dependencies:
      - subtask_id: 1            # must reference an earlier subtask id
        optional: false
        skipped: false
```

The manifest is validated against the decomposition manifest schema contract when the runtime first reads it.

Each subtask spec must contain scope, acceptance criteria, non-goals, dependency notes, validation strategy, and next path. The acceptance-criteria section must follow the **Spec Format Contract** above so the runtime can extract it.

Return the next command as:

```bash
skill-bill goal <issue_key>
```

## Goal Runner Boundary

`skill-bill goal <issue_key>` is consumer-only. It must not synthesize specs from prose. If no decomposition manifest exists, it should continue to loud-fail and direct callers back to preparation.
