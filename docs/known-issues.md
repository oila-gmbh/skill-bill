# Known Issues and Requirements

This file records requirements derived from problems observed during real Skill
Bill runs. Add evidence without copying private phase output, secrets, full
diffs, or unbounded logs.

Use these statuses:

- `open` — confirmed and not scheduled
- `planned` — accepted into a feature spec or issue
- `fixed` — implemented and verified
- `not-reproducible` — retained for context but not currently actionable

## Goal status requirements

- Status: `fixed`
- First observed: 2026-07-29
- Run: `SKILL-150`

### Observed problem

`skill-bill goal status SKILL-150` displayed an old audit schema warning in
`latest_liveness_signal` after the workflow had advanced to review. Repeated
status output made one retained diagnostic look like a recurring failure.

Evidence:

- Workflow: `wftr-20260729-165609-chpi`
- Phase: `audit`
- Attempt: `3`
- Recorded at: `2026-07-29T17:25:47.263781494Z`
- Diagnostic size: `1,387,312` bytes
- Later authoritative step: `review`

### Requirements

- Derive current status from one coherent authoritative snapshot.
- Limit `latest_liveness_signal` to the newest signal for the current workflow
  step.
- Put retained failures in a separate bounded field such as `last_failure`.
- Label historical diagnostics explicitly.
- Include phase, attempt, timestamp, and whether the failure is current.
- Never present a failure from an earlier phase as current after a newer
  authoritative transition.
- Add a regression test where audit output is rejected, execution advances to
  review, and later status calls do not present the audit warning as current.

Verified 2026-07-29 by `GoalRunnerStatusProjectorTest`,
`WorkflowGoalStatusProjectionTest`, and `CliGoalRuntimeTest`.

## Goal watch requirements

- Status: `fixed`
- First observed: 2026-07-29
- Run: `SKILL-150`

### Observed problem

`skill-bill goal watch` mixed the authoritative `current_step: review` with
retained `implement_fix` activity and duplicated the same information across
liveness and observability sections.

### Requirements

The default refresh must show only:

```text
status: <status>
current subtask: <subtask>
current step: <step>
phase: <phase>
timestamp: <timestamp>
```

- Format every refresh consistently.
- Print one blank line between refreshes.
- Derive all displayed fields from one coherent authoritative snapshot.
- Ensure phase agrees with the current step or label it as historical.
- Keep worker role, sequence, activity, raw liveness, and expanded
  observability behind `--verbose` or an equivalent diagnostic option.
- Show phase launches, persisted outputs, retries, and completed loops as
  separate counters. Do not label all of them as attempts without explaining
  what the counter measures.
- Add snapshot tests for formatting, refresh separation, stale-phase handling,
  and counter labels.

Verified 2026-07-29 by `CliGoalWatchRuntimeTest`, `CliGoalRuntimeTest`, and the
goal-status stale-phase projection regression.

## Audit phase requirements

- Status: `open`
- First observed: 2026-07-29
- Run: `SKILL-150`
- Evidence workflow: `wftr-20260729-165609-chpi`

### Observed problems

- Subtask 4 reported two audit gap iterations, nine attempted repair items, and
  nine resolved repair items, but the current projection retained only the
  latest accepted plan with two gaps.
- Earlier gaps, repairs, and iteration relationships could not be reconstructed
  after convergence.
- Audit repair could begin without a plan proving that every carried gap would
  be closed.
- Follow-up audits could reassess the complete subtask and discover unrelated
  gaps in unchanged original scope.
- An audit response of `1,387,312` bytes was classified as `oversized`; its
  exact body was discarded, preventing later investigation.

### Full-audit invariant

- Allow exactly one full audit per subtask workflow.
- The full audit evaluates the complete subtask-owned change against every
  acceptance criterion.
- Persist the immutable audit base, repository checkpoint, satisfied criteria,
  every gap, and stable gap identities.
- Once completed, no retry, resume, repair, repository change, crash recovery,
  or new generation may launch another full audit.
- A new generation preserves evidence history; it never resets the full-audit
  allowance.

### Follow-up audit scope

Every later audit is a bounded continuation covering only:

- unresolved and carried gaps;
- the accepted repair plan and repair results;
- the cumulative incremental delta since the last durably audited checkpoint;
- regressions or defects introduced by those repair changes.

Follow-up audits must not search unchanged portions of the original full-audit
scope for unrelated gaps.

For every continuation:

1. Preserve the original full-audit identity and criterion results.
2. Compute the incremental delta from the last audited checkpoint.
3. Carry every unresolved gap, repair item, result, and disposition.
4. Verify carried gaps against the accepted plan, repair evidence, and
   incremental changes.
5. Inspect incremental changes for defects introduced by the repair.
6. Advance the audited checkpoint only after the continuation is durably
   stored.

If the incremental boundary is missing, rewritten, unrelated, or incompatible,
block resumably. Never fall back to another full audit.

### Durable audit history

Use append-only, normalized records as the durable authority. Retain for every
audit observation:

- stable gap identity;
- source phase attempt and audit iteration;
- first-seen and last-seen iteration;
- acceptance-criterion reference;
- classification as new, recurring, still open, resolved, or superseded;
- diagnosis and affected boundary;
- repair-item identities and dependencies;
- repair attempt and outcome;
- bounded verification evidence;
- disposition iteration and final status.

Append a generation for every schema-valid audit result, including a no-gaps
verification. Derive aggregate counters and bounded latest-state projections
from these records without replacing history.

### Audit remediation plan

Before entering `implement_fix`, create and validate one closure-complete plan
containing every unresolved and carried gap.

For each gap, the plan must include:

- stable identity and source iteration;
- unsatisfied acceptance criterion and exact failing behavior;
- root cause;
- invariant or observable behavior required after repair;
- affected boundaries, symbols, paths, and consumers;
- concrete implementation actions;
- dependencies and execution order;
- regression risks and interactions with other gaps;
- verification capable of failing before the repair and passing afterward;
- expected bounded evidence and final disposition.

Every gap maps to at least one repair item, and every repair item maps back to
one or more gaps. Group related symptoms under one root-cause repair where
appropriate.

Accept the plan only when:

- every unresolved gap is covered;
- actions address the governing invariant rather than only the reported line;
- important boundary variants and dependencies are covered;
- verification can prove closure;
- no closure depends only on an agent confidence statement;
- the plan fits the bounded repair policy.

Persist the exact accepted plan and reuse it across retry, resume, and crash
recovery.

### Audit plan retry and resumable block

1. Validate the initial remediation plan.
2. If it cannot prove closure, return the complete rejection receipt and allow
   exactly one replan.
3. Give the replan every carried gap, the rejected plan, validation failures,
   missing evidence, dependencies, verification, and repository checkpoint.
4. If the second plan still cannot prove closure, block before
   `implement_fix`.

The durable blocked state must retain both rejected plans, every unresolved
gap, the exact failure reason, workflow and attempt identities, checkpoint,
immutable execution identity, last resumable step, and safe operator actions.

Resume must reuse that state and continue from audit remediation planning. It
must preserve the consumed replan budget unless a governed reset occurs.

### Audit repair closure

For every repair item, record:

- changed boundaries;
- implementation outcome;
- executed verification;
- bounded result evidence;
- disposition of every mapped gap;
- final status of fixed, already satisfied, recurring, governed superseded, or
  still open.

Audit cannot clear until every carried gap and repair item has an
evidence-backed terminal disposition. The verification audit receives the
complete carried gap set, accepted plan, and repair results.

### Rejected audit output

- Persist every rejected audit response as exact bytes in a SQLite BLOB,
  regardless of size or rejection reason.
- Include schema-invalid, malformed, unparseable, semantically incomplete,
  projection-invalid, nonzero-exit, retry-gate, and replan-gate output.
- Store the BLOB and metadata atomically in one transaction.
- Retain workflow, phase, attempt, rule, path, reason, byte size, digest, media
  type, lifecycle, and access metadata.
- Retain records across retries, resumes, crashes, completion, and restart
  until explicit governed cleanup.
- Stream full, head, tail, range, and summarized retrieval without loading the
  body into memory.
- Keep raw output out of status, watch, telemetry, prompts, PR text, and normal
  workflow projections.
- Treat persistence failure as a typed blocking storage failure. Never discard
  the body into a metadata-only `oversized` record.

### Audit observability and tests

- Expose bounded audit history and remediation views showing each iteration,
  gap, plan, repair result, verification, and disposition.
- Report full-audit count, continuation count, phase launches, rejected
  outputs, and current incremental checkpoint separately.
- Test one-full-audit enforcement, incremental verification, stable gap
  identity, append-only history, plan completeness, successful one-time
  replan, resumable second-plan failure, crash recovery, stale checkpoints,
  multi-megabyte rejected output, streaming retrieval, cleanup, and migration.

## Review phase requirements

- Status: `open`
- First observed: 2026-07-29
- Run: `SKILL-150`
- Evidence workflow: `wftr-20260729-165609-chpi`

### Observed problems

- Subtask 4 launched the review phase thirteen times, persisted eight review
  passes, and completed four two-pass cycles.
- Each cycle reopened a full review of the complete subtask delta.
- Related defects reappeared with new finding identities and different wording,
  obscuring recurrence and closure.
- Remediation could begin without a plan proving that every carried Blocker
  would be closed.
- The current data did not explain five review launches that produced no
  persisted generation.
- Full raw output was not retained for non-approved review results, leaving only
  bounded text that could end mid-sentence.

### Full-review invariant

- Allow exactly one full review per subtask workflow.
- The full review uses the immutable `review_base_sha` and baseline untracked
  inventory to inspect the complete subtask-owned delta.
- Persist its immutable base, checkpoint, generation, result identity, and
  stable finding identities.
- Once completed, no retry, resume, repository change, invalidation,
  remediation, crash recovery, cap, or new generation may launch another
  full-scope review.
- A new generation preserves evidence history; it never resets the full-review
  allowance.

### Follow-up review scope

Every later review is a bounded continuation covering only:

- unresolved and carried findings;
- the accepted remediation plan and repair results;
- the cumulative incremental delta since the last durably reviewed checkpoint;
- defects introduced by those incremental changes.

Follow-up reviews must not rediscover unrelated findings in unchanged portions
of the original full-review scope.

For every continuation:

1. Preserve the original full-review identity and immutable scope.
2. Compute the incremental delta from the last reviewed checkpoint.
3. Carry every unresolved finding and its complete disposition history.
4. Verify carried findings against the accepted plan, repair evidence, and
   incremental changes.
5. Inspect incremental changes for defects introduced by remediation.
6. Advance the reviewed checkpoint only after the continuation result is
   durably stored.

If the incremental boundary is missing, rewritten, unrelated, or incompatible,
block resumably. Never fall back to another full review.

### Durable review history

Use append-only, normalized records as the durable authority. Retain:

- stable logical finding identity across wording and location changes;
- source phase attempt, generation, and pass;
- first-seen and last-seen generation;
- classification as new, recurring, still present, resolved, superseded, or
  accepted through a governed operator action;
- severity, category, diagnosis, and affected boundary;
- remediation-plan and repair-item mappings;
- repair attempts, results, and verification evidence;
- every disposition and its generation;
- phase-launch outcomes, including why a launch did not produce a generation.

Derive counters and bounded current projections from these records without
replacing history.

### Review remediation plan

Before entering `implement_fix`, create and validate one closure-complete plan
containing every unresolved and carried Blocker.

For each finding, the plan must include:

- stable identity and source generation/pass;
- root cause;
- invariant required after repair;
- affected boundaries, symbols, and paths;
- concrete implementation actions;
- dependencies between repair items;
- regression risk and interaction with other findings;
- verification capable of failing before the repair and passing afterward;
- expected disposition evidence;
- bidirectional mapping between findings and repair items.

Accept the plan only when:

- every unresolved Blocker is covered;
- related findings are grouped under one root cause where appropriate;
- actions address the invariant rather than only the reported line;
- verification covers the failure mode and important variants;
- no closure depends only on an agent confidence statement;
- the plan fits the bounded remediation policy.

Persist the exact accepted plan and reuse it across retry, resume, and crash
recovery.

### Review plan retry and resumable block

1. Validate the initial remediation plan.
2. If it cannot prove closure, return the complete rejection receipt and allow
   exactly one replan.
3. Give the replan every carried finding, the rejected plan, validation
   failures, missing evidence, dependencies, verification, and repository
   checkpoint.
4. If the second plan still cannot prove closure, block before
   `implement_fix`.

The durable blocked state must retain both rejected plans, every unresolved
finding, the exact failure reason, workflow and attempt identities, checkpoint,
immutable execution identity, last resumable step, and safe operator actions.

Resume must reuse that state and continue from review remediation planning. It
must preserve the consumed replan budget unless a governed reset occurs. No
operator action may silently accept unresolved Blockers.

### Review repair closure

For every repair item, record:

- changed boundaries;
- implementation outcome;
- executed verification;
- bounded result evidence;
- disposition of every mapped finding;
- final status of fixed, already satisfied, still present, governed
  superseded, accepted through explicit operator action, or unresolved.

Review cannot advance until every carried Blocker and repair item has an
evidence-backed terminal disposition. The continuation review receives the
complete carried finding set, accepted plan, and repair results.

### Rejected and non-approved review output

- Persist every rejected or non-approved review response as exact bytes in a
  SQLite BLOB, regardless of size or rejection reason.
- Include schema-invalid, malformed, unparseable, semantically incomplete,
  projection-invalid, changes-requested, nonzero-exit, retry-gate, and
  replan-gate output.
- Store the BLOB and metadata atomically in one transaction.
- Retain workflow, phase, attempt, generation, pass, rule, path, reason, byte
  size, digest, media type, lifecycle, and access metadata.
- Retain records across retries, resumes, crashes, completion, and restart
  until explicit governed cleanup.
- Stream full, head, tail, range, and summarized retrieval without loading the
  body into memory.
- Keep raw output out of status, watch, telemetry, prompts, PR text, and normal
  workflow projections.
- Treat persistence failure as a typed blocking storage failure. Never discard
  the body into a metadata-only lifecycle.

### Review observability and tests

- Expose bounded review history and remediation views showing each generation,
  finding, plan, repair result, verification, and disposition.
- Report full-review count, continuation count, phase launches, persisted
  passes, retries, rejected outputs, and current incremental checkpoint
  separately.
- Test one-full-review enforcement, incremental continuation, stable finding
  identity, append-only history, plan completeness, successful one-time replan,
  resumable second-plan failure, crash recovery, stale checkpoints,
  multi-megabyte rejected output, streaming retrieval, cleanup, and migration.
