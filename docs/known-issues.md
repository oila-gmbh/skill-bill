# Known Issues

This file records problems observed during real Skill Bill runs that are worth
fixing later. Add evidence without copying private phase output, secrets, full
diffs, or unbounded logs.

Use these statuses:

- `open` — confirmed and not scheduled
- `planned` — accepted into a feature spec or issue
- `fixed` — implemented and verified
- `not-reproducible` — retained for context but not currently actionable

## KI-001 — Goal status repeats a retained warning as if it were current

- Status: `open`
- First observed: 2026-07-29
- Run: `SKILL-150`
- Area: goal status and observability projection

### Problem

`skill-bill goal status SKILL-150` repeatedly displayed an old audit schema
warning in `latest_liveness_signal` after the workflow had advanced to review.
The output did not make clear that this was a retained historical signal, so it
looked like the same failure was recurring.

### Evidence

- Workflow: `wftr-20260729-165609-chpi`
- Phase: `audit`
- Attempt: `3`
- Recorded at: `2026-07-29T17:25:47.263781494Z`
- Diagnostic lifecycle: `oversized`
- Diagnostic size: `1,387,312` bytes
- Later authoritative step: `review`

Only one rejected diagnostic existed for this attempt. Repeated status output
showed the same retained warning rather than a new rejection.

### Expected behavior

Status output must distinguish current execution state from retained historical
diagnostics. A warning from an earlier phase or attempt should include its
timestamp, source phase, attempt, and a label such as `historical` or
`last_failure`. It must not appear inside a field named or presented as the
latest live signal after a newer authoritative state transition.

### Possible fix

- Keep `latest_liveness_signal` limited to the newest signal for the current
  workflow step.
- Project retained failures into a separate bounded field such as
  `last_failure`.
- Include phase, attempt, timestamp, and whether the failure is current.
- Add a regression test where audit output is rejected, the workflow advances
  to review, and subsequent status calls do not present the audit warning as a
  current failure.

## KI-002 — Goal watch refresh output is noisy and internally inconsistent

- Status: `open`
- First observed: 2026-07-29
- Run: `SKILL-150`
- Area: goal watch presentation

### Problem

`skill-bill goal watch` emits a dense refresh block containing overlapping
status, liveness, activity, and observability fields. The authoritative goal
state can say `current_step: review` while the same refresh prominently shows
retained `implement_fix` worker output. The duplication and mixed timeframes
make the live feed difficult to scan and easy to misinterpret.

Observed shape:

```text
watch_refresh: index=1310
  status: ok
  current_subtask: 4
  current_step: review
  execution_liveness: live
  latest_liveness_signal: liveness=worker_output_summary phase=implement_fix ...
  observability:
    phase: implement_fix
    worker_role: goal_runner_supervisor
    liveness: worker_output_summary
    sequence: 10056
```

### Expected behavior

The default watch feed should show only:

```text
status: <status>
current subtask: <subtask>
current step: <step>
phase: <phase>
timestamp: <timestamp>
```

Each refresh must be formatted consistently and separated from the next
emission by a blank line. Default output should not repeat raw liveness,
activity, role, sequence, or observability details. Those diagnostics may
remain available behind an explicit verbose or debug option.

The displayed phase must either agree with the current authoritative step or
be clearly labeled as historical.

### Possible fix

- Introduce a compact default renderer shared by `goal watch` refreshes.
- Derive status, subtask, step, phase, and timestamp from one coherent
  authoritative snapshot.
- Print one blank line between refresh blocks.
- Move worker role, sequence, activity, raw liveness, and expanded
  observability fields behind `--verbose` or an equivalent diagnostic option.
- Add snapshot tests for formatting, refresh separation, and stale-phase
  handling.

## KI-003 — Converged audit state does not retain complete iteration history

- Status: `open`
- First observed: 2026-07-29
- Run: `SKILL-150`
- Subtask: `4`
- Workflow: `wftr-20260729-165609-chpi`
- Area: audit convergence persistence and status

### Problem

The durable summary reports two audit gap iterations, nine attempted repair
items, and nine resolved repair items. The current artifact projection retains
only the latest accepted plan with two gaps, `AC-004` and `AC-011`. It is
therefore impossible to reconstruct all nine repair items, determine exactly
which gaps were found in each iteration, or compare the first audit with the
second after convergence.

Aggregate counters prove that earlier evidence existed but do not explain:

- which gap was first found in which audit iteration;
- whether a later observation was new, recurring, or caused by a repair;
- which repair item addressed each gap;
- when and how each repair was verified;
- whether a gap was resolved, superseded, or reopened in a later iteration.

### Expected behavior

Audit convergence history must be append-only and queryable by workflow and
iteration. Completion may produce a bounded latest-state projection, but it
must not replace or discard earlier gap and repair records.

For every audit observation, retain:

- stable gap identity;
- audit iteration and phase attempt;
- first-seen and last-seen iteration;
- acceptance-criterion reference;
- classification as new, recurring, still open, resolved, or superseded;
- diagnosis and affected boundary;
- repair-item identities and dependencies;
- repair attempt and outcome;
- bounded verification evidence;
- disposition iteration and final status.

The CLI should expose a bounded history view, for example:

```text
audit iteration 1
  GAP-1  AC-004  new       fixed in repair iteration 1
  GAP-2  AC-007  new       fixed in repair iteration 1

audit iteration 2
  GAP-1  AC-004  resolved  verification passed
  GAP-3  AC-011  new       fixed in repair iteration 2
```

### Possible fix

- Make normalized audit generation, gap, repair-item, result, and disposition
  records the durable authority.
- Append a generation for every schema-valid audit result, including a
  no-gaps verification generation.
- Preserve stable gap identity across generations and store recurrence
  explicitly.
- Derive aggregate counters and the bounded current projection from the
  append-only records.
- Add an `audit-history` CLI view or an equivalent option on feature-task
  status.
- Add migration and regression coverage proving two audit iterations remain
  fully explainable after completion, resume, changed repository state, and
  cleanup.

## KI-004 — Review remediation starts without a closure-complete repair plan

- Status: `open`
- First observed: 2026-07-29
- Run: `SKILL-150`
- Subtask: `4`
- Workflow: `wftr-20260729-165609-chpi`
- Area: review remediation convergence

### Problem

Review Blockers repeatedly survived remediation or reappeared under new finding
identities. The remediation flow can enter `implement_fix` without first
persisting a plan that covers every carried Blocker and explains how the
proposed changes will prove closure.

For example, review-disposition evidence validation appeared across multiple
passes as line-range handling, active-checkpoint comparison, fabricated-path
acceptance, and absent-hunk handling. Checkpoint persistence also reappeared in
different forms. Local fixes addressed individual symptoms without proving the
full invariant.

### Expected behavior

When review produces one or more unresolved Blockers, the runtime must create
and validate one closure-complete remediation plan before implementation
resumes. The plan must include every carried Blocker from prior and current
review generations.

For each finding, retain:

- stable logical finding identity;
- source generation and pass;
- root-cause statement;
- intended invariant after repair;
- affected boundaries, symbols, and paths;
- concrete implementation actions;
- dependencies between repair items;
- regression risk and interaction with other carried findings;
- required verification that would fail before the fix and pass afterward;
- expected disposition evidence;
- explicit mapping from every finding to at least one repair item and from
  every repair item back to its findings.

The plan is accepted only when:

- every unresolved Blocker is covered;
- related findings are grouped under one root cause where appropriate;
- proposed actions address the invariant rather than only the reported line;
- verification exercises the failure mode and important variants;
- no finding relies only on an agent confidence statement;
- the plan fits within the bounded remediation policy.

`implement_fix` must receive this exact persisted plan. Resume and crash
recovery must reuse it rather than regenerate or narrow it.

### Closure gate

After implementation, the agent must produce a result for every repair item:

- changed boundaries;
- executed verification;
- bounded evidence;
- outcome of `fixed`, `already_satisfied`, or governed `superseded`;
- dispositions for every mapped finding.

Review may advance only when the runtime verifies plan completeness and result
completeness. The next review pass receives the full carried finding set, the
repair plan, and the repair results so it can verify closure directly.

### Possible fix

- Add a versioned review-remediation-plan contract.
- Persist plans, repair items, results, and finding mappings in normalized,
  generation-aware records.
- Add a producer-side completeness gate before entering `implement_fix`.
- Reject a repair result that omits a carried finding or required verification.
- Preserve stable logical finding identities across wording and location
  changes.
- Show the active plan and per-finding closure state through a bounded
  `review-history` or `review-remediation` CLI view.
- Add regression coverage where one root defect appears as several findings
  and cannot advance until the plan and verification cover every variant.
