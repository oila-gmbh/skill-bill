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
