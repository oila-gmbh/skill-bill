---
internal-for: bill-feature
name: bill-feature-task-runtime
description: "Runtime-backed bill-feature-task via foreground `skill-bill feature-task`. Use when implementing a feature/spec with the runtime phase loop."
---

# Feature Task Content

`bill-feature-task-runtime` is the runtime-backed mode for running a single
governed spec through the runtime-driven feature-task phase loop
(`plan -> implement -> audit -> review -> validate`) owned by the local
`skill-bill feature-task` driver.

Durable workflow rows use the public workflow identity `bill-feature-task` with
`mode=runtime` in the shared feature-task workflow store. Runtime-specific tool
names are compatibility aliases for that mode, not a separate authoritative
workflow family.

The workflow database is the continuation authority. Resume keeps the existing workflow id and validates the issue key, canonical repository identity, persisted governed spec path, and runtime mode before branch preparation or phase launch. Completed durable phases remain skipped. Initial implementation hydrates from the completed `plan`. Audit-gap implementation remediation reuses the original completed `preplan` and `plan` outputs; neither planning phase is relaunched or overwritten.

For a goal child, hydrated preplan and plan records carry goal-planning provenance: no child agent is launched for them and their payloads, duration, tokens, and agent identity are not counted as child execution. A standalone feature task is unchanged and executes and attributes its own preplan and plan directly to the standalone workflow.

`bill-feature-task-runtime` consumes the normalized, router-confirmed run and
launches the runtime command. It does **not** re-implement phase orchestration
in prose — the runtime owns the phase loop, the per-phase handoff, the schema
gates, and the durable state. This skill must never restate or re-derive that
orchestration.

## Confirmed Input

Consume the router-confirmed run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)
- the normalized review selection from `code-review:auto|inline|delegated`
- the already-resolved ordered agent add-on selection, if present

The `bill-feature-task` router has already rejected invalid review-selection
tokens and presented the only confirmation gate. Do not reparse, default, or
change `code-review:<selected-mode>`, and do not ask another confirmation
question. If the router failed to provide the issue key, spec path, or
normalized selection, stop rather than inventing a value. The runtime sources
the run-invariants (spec reference, acceptance criteria, mandates and overrides)
directly from the spec at launch — this skill does not parse or restate them.

## Fresh-conversation follow-up

When continuing from a fresh conversation, pass only the canonical repository realpath
and issue key to the runtime continuation lookup. Those values are sufficient durable-state handoff data:

```text
repository: repo-root-realpath-v1:/absolute/path/to/repository
issue_key: SKILL-154
```

Inspect or resume the existing workflow state for that repository and issue key.
Do not copy transcripts or transfer preplan, plan, implementation, audit, review,
validation, diagnostic, or raw child payloads into the new session.

## Runtime Launch

Execute the foreground driver directly in the current agent session, always
passing `--agent` set to the agent currently executing this skill:

```bash
skill-bill feature-task run <issue_key> <spec_path> --agent <currently-executing-agent>
```

Append `--parallel-review-agent <agent>` when `parallel-review:<agent>` was passed to this skill.
Append `--code-review-mode <auto|inline|delegated>` using the resolved selection.
Append `--agent-addon-selection-json <structured-json>` when a selection was
provided. Do not parse, reorder, or rediscover it. Runtime preparation verifies
each recorded source identity and exact-byte digest before workflow, branch, or
phase side effects and injects only verified selected content into prompts.

Always pass `--agent` set to the agent currently running this skill (for example
`claude` from Claude Code or `codex` from Codex), so the
invoking agent — not a hardcoded default — drives the phase runs. Only use
`--agent-override` when the user explicitly selected a different agent;
`--agent-override` wins over `--agent`. An optional repeatable
`--phase-agent <phase-id>=<agent-id>` (for example `--phase-agent plan=claude`)
assigns a specific agent to one phase.

Do not ask the user to run this command manually. Keep the run in the foreground
unless the user asks otherwise; pass `--monitor` to tee phase transitions to the
terminal.

For a goal-continuation child, durable `install_sync_result.status=deferred`
means the parent owns install refresh after the active goal exits. Never run an
installer, uninstaller, or install-sync command from the child, and never block
subtask completion solely because install sync is deferred. Record the deferred
work in the phase result or review notes and continue evaluating every other
acceptance criterion normally.

### Progress Visibility

The terminal monitoring block is the user's live feed. The invoking agent does
not attach an observer to the progress stream and does not relay transitions
into the conversation. There is no in-session transition relay; agent silence
during the run is deliberate, not a failure, and ends only when a sanctioned
completion signal or error reaches the session.

After launch, keep the session on the original foreground feature-task runtime
blocking call until it returns, or keep the original process alive across yields
and await its exit through the harness process-completion primitive. That single
long wait is the completion signal. It is required, not optional, and it is not
progress observation — the agent makes no separate tool calls while that call runs.

While a foreground or detached run is in flight:

1. Do not run `skill-bill goal watch` in-session, at any interval or refresh count.
2. Do not call `skill-bill feature-task status <workflow_id>` on a timer or
   repeatedly to observe change.
3. Do not sleep between separate progress checks, schedule wake-ups, or otherwise
   idle between tool calls whose only purpose is to re-read progress.
4. Do not tail, poll, or re-read runtime logs, the workflow DB, `git diff`, or
   changed files to infer progress.
5. Do not re-invoke the runtime or launch an observer process or subagent to
   observe a run that is already executing.

These prohibitions apply to shell loops, scheduled wake-ups, repeated tool
calls, and delegated observers. The cost rule is request count, not wall-clock
time: one completion signal — one blocking launch call or one background-exit
re-invocation — beats any number of short polls, and trimming a poll's output
does not make polling acceptable. A multi-hour blocking wait on the launch
command is the completion signal, not token waste from observing.

The only permitted in-session surface is exactly one completion line, errors
such as launch failures, loud-fails, or non-zero exits, and one
`skill-bill feature-task status <workflow_id>` call made in direct response to an explicit user
request.

`--monitor` remains required for feature-task-runtime because its output scales
with phase count. Quiet `--no-live-output` launch applies only to `goal`, whose
live output scales with wall-clock duration.

#### Completion Signal

Use the completion signal for the launch mode:

1. For a foreground run within the harness timeout, wait for the blocking call
   to return its structured result.
2. For a detached run where the harness provides background-exit notification,
   let that notification re-invoke the agent once with the result; do not poll.
3. When the harness provides no background-exit notification, do not detach.
   Keep ownership of the original foreground process and use the harness's
   blocking process-completion primitive to await that process's exit. Waiting
   on the original process is a completion signal, not progress polling: do not
   re-read status, logs, workflow state, or process output while it runs. If the
   harness cannot keep or await the original process, loud-fail before launch
   because the session cannot guarantee terminal delivery. Do not substitute a
   `skill-bill feature-task status <workflow_id>` snapshot for the structured
   terminal result.

When the outcome reaches the session, emit exactly one completion line. Compose
it only from the feature-task structured result fields `status`, `workflow_id`,
`completed_phases`, `last_incomplete_phase`, and `blocked_reason`:

```text
feature-task ft-run-01J8Z0-SKILL-141: complete — 9 phases completed
feature-task ft-run-01J8Z0-SKILL-141: blocked at review — <blocked_reason>
feature-task ft-run-01J8Z0-SKILL-141: failed — <error>
```

Do not read back, summarize, or paraphrase run stdout to compose the completion
line. Do not emit progress or transition lines around it.

#### Required: print the terminal monitoring command

Printing a copy-pasteable monitoring command is required, not optional. As soon
as the workflow id exists and before reporting any phase progress, emit a
copyable block the user can paste into a separate terminal, with real values
already substituted — never placeholder text such as `<workflow_id>`:

```bash
skill-bill feature-task status ft-run-01J8Z0-SKILL-141
```

State alongside it that the command is read-only, mutates nothing, consumes no
model tokens, and can be run in a second terminal as often as the user likes
while the run continues. Also state that the user can ask this session for status
at any point; when they do, run the command and report what it returns.

If the workflow id is not yet known, print the discovery command in the same
block first:

```bash
skill-bill feature-task lookup SKILL-141 --repo-root .
```

Two obligations survive:

- Report the terminal result only through the single structured completion line
  defined above. On a blocked or failed gate, surface that line loudly and
  immediately and stop — never narrate a blocked run as if it were progressing.
- Never re-derive or re-order the phase loop. The durable workflow state is
  authoritative over any terminal line.

The runtime owns everything after launch: it opens the durable runtime workflow,
runs each phase through its own agent, validates each phase output against the
schema gate, persists per-phase state, and blocks loudly on a failed
non-validation gate or a missing upstream output. The validate agent runs only the
pack-declared collect-all command, reads that output, fixes every finding in that session,
then runs that same command once to confirm. It must not run `skill-bill validate`,
`npx agnix`, `scripts/validate_agent_configs`, or any other repo-root checklist.
Remaining findings after that session persist
`validate` as blocked with `findings_open`; resume starts one new session over
that set instead of spawning another agent in the same visit. Treat the durable
workflow state as authoritative over any prose.

## Audit-first review gate

The authoritative phase order is `implement -> audit -> review -> validate`.
Audit-gap repair re-enters only `implement -> audit`; after audit is satisfied,
review pass one uses the selected delegated mode and every later remediation
pass is inline. Review never reopens audit.

## Review-driven implement-fix loop

The runtime closes an unbounded remediation loop around `review`. The `review`
phase emits a structured verdict derived from its findings: `approved` when no
unresolved Blocker or Major findings remain, or `changes_requested` when any are
present. A remediation round is handed all findings. Blocker and Major findings
both reopen `implement_fix` and block advancement. The runtime evaluates that
verdict — prose alone cannot advance past a Blocker or Major finding.

- On `approved`, the run advances to `validate` (a clean run never launches a fix).
- On `changes_requested`, the runtime takes a backward edge to a dedicated
  `plan_fix` phase, which decides the root cause of each carried finding before
  any edit is made, then to `implement_fix`, which addresses the carried findings
  on the current working tree as incremental reconciliation (not a plan
  re-application), then re-runs `review`. `plan_fix` mutates nothing and never
  regenerates, mutates, or overwrites the durable `preplan` or `plan` outputs: it
  reads them, the carried findings, and the remediation repair ledger, and emits
  its own bounded `repair_plan` naming per finding the root cause, the minimal
  change that addresses it, and a `local_patch_site` or `design_symptom`
  classification. Both fix phases are loop-only, so a clean run launches neither,
  and the `plan_fix` → `implement_fix` step is a declared loop-only successor
  rather than a second backward edge, so one round still mints exactly one
  `review_fix` iteration. This `review` → `plan_fix` → `implement_fix` → `review`
  cycle carries no iteration cap: it continues while any Blocker or Major finding remains. A
  durable per-edge counter with PER_SUBTASK scope accumulates across parent
  resumes for accounting and warning purposes only. Goal status reports cumulative
  iteration counts across all runs for the same subtask. The initial review may
  use the selected mode, while every re-review is reserved before launch and
  always invokes `bill-code-review mode:inline context:feature-remediation` against
  the remediation delta — all findings addressed in that round unioned with the
  pre-fix-to-post-fix diff since the checkpoint created before `implement_fix`
  — never the full feature-branch diff. The first `approved` verdict advances the
  run to `validate`.
- Each `implement_fix` round emits a durable, construct-granular repair receipt, and
  the runtime folds those receipts together with the review pass results into the
  remediation repair ledger: per closed finding, the constructs holding it closed
  and a status drawn from a closed vocabulary — `resolved` (the remedy stands),
  `superseded` (a later round's remedy replaced the constructs holding it), and
  `reopened` (a later pass reported an advance-blocking finding against those
  constructs). The ledger is derived from durable receipts and pass results rather
  than stored as a second record, so it survives process death and parent resume
  without drifting from what the rounds actually recorded. `plan_fix` and
  `implement_fix` receive it as a bounded, named, versioned projection; the
  remediation review pass receives it as delimited reference material that is
  escalation signal only and never softens a finding's severity. An `implement_fix`
  round that rewrites a construct the ledger records as another finding's live
  remedy must declare that finding and its rationale, or its receipt is rejected.
- Crossing from iteration 3 to iteration 4 prints a user-visible warning that the
  advisory threshold of 3 was exceeded and remediation continues. The threshold is
  a warning signal, not a cap: it never stops, pauses, or advances the run.
- When `plan_fix` classifies a carried finding as a `design_symptom` — a
  consequence of an earlier round's remedy rather than a local defect — it settles
  the `escalated` verdict instead. `escalated` has no successor and no backward
  edge, so it neither advances to `implement_fix` nor routes back to `plan`; it
  pauses the subtask on the existing resumable operator pause with the repair
  ledger and the root-cause analysis as durable evidence, released through the
  existing `retry_fix`, `accept_and_advance`, and `abandon_subtask` decisions.
- The loop ends only on an `approved` verdict, an `escalated` operator pause, or an
  existing non-count-based failure, non-convergence, or churn path. Non-convergence is the same unresolved
  Blocker-or-Major set with no repository change. Churn is its widened companion: advance-blocking findings
  recurring against constructs the repair ledger already records as repaired, across 3 consecutive rounds,
  with an advance-blocking finding set that is not shrinking. Churn is what a changing finding set against a
  changing tree produces, which the non-convergence condition alone never detects. Both pause as a
  human-resumable, uncapped block released through the same `retry_fix`, `accept_and_advance`, and
  `abandon_subtask` decisions; an active retry grant suppresses either for exactly one transition, and
  neither ever abandons or auto-accepts an unresolved Blocker or Major. Minor and Nit findings are written to the
  goal-wide ledger and never hold the loop open.

The loop is crash-safe: a death during `plan_fix`, `implement_fix`, or a re-`review` resumes
at the correct phase and iteration with no double-applied mutations, and the
recovered iteration count resumes warning accounting rather than terminating
remediation.
Each `plan_fix` launch, `implement_fix` launch, and re-`review` carries the `review_fix` loop id
and iteration in the ledger and status output, and finished telemetry reflects
the review-fix iteration count.

## Audit-gap context-reuse implementation-remediation loop

The runtime closes a second, wider remediation loop around `audit`. The
`audit` phase emits a structured verdict derived from the acceptance criteria it
checked: `satisfied` when every criterion is met, or `gaps_found` when one or
more remain unmet. The runtime evaluates that verdict — prose alone cannot
advance past an unmet acceptance criterion.

Audit gaps are restricted to concrete defects in production behavior or
production implementation. Missing tests, weak tests, incomplete coverage,
unrealistic fixtures, insufficient assertions, and every other test-only concern
are never audit gaps. Audit does not assess test adequacy, cite test files as the
affected boundary, or create repair items that add or change tests. Test execution
and test failures belong to validation. When no production defect is evidenced,
audit emits `satisfied` even if test coverage is absent or inadequate.

- On `satisfied`, the run advances to `review`.
- On `gaps_found`, the runtime takes a backward edge re-entering `implement`,
  then `audit`. The implementation handoff contains the immutable
  original `preplan` and `plan` outputs plus the complete durably accepted audit
  repair plan and cumulative unresolved-gap ledger. Every gap has a stable id,
  criterion reference, evidence, diagnosis, boundary, and dependency-ordered
  repair items. One remediation invocation repairs the complete carried gap set,
  honoring internal dependencies without launching a separate pass per gap, and
  emits an exact terminal result for every repair item; review or validation
  cannot substitute for executing carried work. This
  `audit` → `implement` → `audit` cycle has no
  iteration cap — repair and re-audit continue while any blocking gap remains —
  and uses PER_SUBTASK counter scope: the iteration counter
  accumulates across parent resumes. Crossing from iteration 3 to iteration 4
  prints a user-visible warning that the advisory threshold of 3 was exceeded and
  remediation continues; the threshold never caps the loop. Goal status reports
  cumulative iteration counts across all runs for the same subtask. The durable
  counter records progress and recovery state but never turns a valid `gaps_found`
  verdict into a permanent policy block. The first `satisfied` verdict advances
  the run to `review`.

The re-entered `implement` is idempotent: it reconciles the working tree toward
the original plan without double-applying, and a crash mid-loopback resumes at the
correct phase and iteration while preserving the accepted plan, stable ids, and
`audit_gap` watermark. The initial audit checks the full open acceptance-criterion
surface once. Every following audit checks only the carried unresolved gaps and
the repair work performed for them in that round. It classifies each carried gap
as resolved or recurring; it does not rescan the full subtask, cumulative diff,
or unrelated acceptance surface and does not discover new gaps. Equivalent
recurring gap sets without repository change or newly resolved repair items block
loudly as non-progress instead of looping indefinitely.

Review begins only after the audit-gap loop is closed. Its first pass uses the
selected delegated mode and every later remediation pass is inline. Each backward
edge carries the `audit_gap` loop id and iteration in the
ledger and status output, and finished telemetry reflects the audit-gap
iteration count alongside the review-fix count.

Location-bearing finding evidence is returned only by
`skill-bill goal findings --issue-key <KEY>`. Goal, status, watch, telemetry,
and PR output may expose compact counts or sanitized summaries only.

## Status and Resume

Status is read-only and never starts a run:

```bash
skill-bill feature-task status <workflow_id>
```

Report the complete, pending, and blocked phase counts, the current phase, and
each phase's status exactly as returned. Do not mutate state during a
status-only request.

To resume an interrupted run against its existing workflow id:

```bash
skill-bill feature-task resume <workflow_id> <issue_key> <spec_path> --agent <currently-executing-agent>
```

Resume re-runs the runtime phase loop, which deterministically skips
already-complete phases from the durable per-phase records. If the runtime blocks
a phase, summarize the blocked phase and reason rather than continuing the loop
manually.

Phase failures carry a durable typed disposition. `retryable`,
`process_failure`, and `invalid_output` may relaunch under the applicable
bounded policy. `non_retryable_policy_conflict` and `needs_user_action`
re-block unchanged on resume without launching another agent or consuming an
attempt. Do not override this decision in-session.

Every launched phase records its before, after, and introduced changed-path
manifests. If a phase introduces a governed `.feature-specs/` path for another
issue, the runtime records a non-retryable policy block. A path already dirty
before the phase remains evidence but is not attributed to that phase. Do not
work around this guard by committing, staging, or renaming the unrelated spec.

`commit_push` is runtime-owned. Emit `commit_push_result` with a non-blank
`message` and an enumerated `changed_paths` list only; do not run `git commit` or
`git push` for the subtask deliverable. The runtime stages the path set, amends the
subtask commit to the outcome message, captures the post-amend sha, pushes once,
and prunes `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/*` after the
manifest records a non-blank `commit_sha`. Governed `.feature-specs/` paths stay
unstaged throughout.

To deliberately replace a nonterminal run, terminalize that exact workflow
through the supported operator path:

```bash
skill-bill feature-task abandon <workflow_id> --reason "<operator reason>"
```

Abandonment requires the exact workflow id and a non-blank reason, records the
reason and timestamp in durable workflow artifacts, preserves phase records and
ledger history, and rejects unknown or already-terminal workflows. Never edit
SQLite directly to make continuation lookup select a replacement.

If a legacy nonterminal runtime workflow loud-fails because it predates the
immutable execution-identity contract, repair only the missing identity through
the explicit operator seam:

```bash
skill-bill feature-task repair-identity <workflow_id> <issue_key> <spec_path> \
  --repo-root <repo-root> --reason "<operator reason>"
```

The repair canonicalizes repository and governed-spec paths, requires the
persisted issue key to agree, records repair evidence, and preserves immutable
identity conflict checks. It never guesses identity or silently migrates a
workflow during resume.

### Rehydrate a missing linear-mode spec before resume

The spec source is the sibling `decomposition-manifest.yaml` `spec_source`
artifact stamp, defaulting to `local` when omitted. A bare `spec.md` is preparation
intake, not prepared source authority. For `spec_source: local`, resume needs no
extra step.

For `spec_source: linear`, the local spec scratch is deleted on terminal success,
so before calling `resume` check whether the file at `<spec_path>` (or a needed
subtask spec) exists. If it is missing, rehydrate it first: fetch the parent
issue by `issue_key` and the subtask by its `linear_issue_id` via the Linear MCP,
rewrite the local spec file(s), and only then call `resume`. The runtime read
path is unchanged — it still reads `<spec_path>` once and freezes invariants;
rehydrate only guarantees the file is present first. Rehydrate is agent-side MCP
only; the runtime gains no Linear dependency.

## Audit-first review and findings ledger

The phase order is `implement -> audit -> review -> validate`, and review is gated on a satisfied audit. Review pass one uses the selected mode, and every later pass runs inline against the remediation delta via `context:feature-remediation`. Blocker and Major findings prevent advancement; Minor and Nit findings advance and are persisted in the goal-wide unaddressed-findings ledger. Location-bearing detail is available only through `skill-bill goal findings --issue-key <KEY>`, during or after the goal.
