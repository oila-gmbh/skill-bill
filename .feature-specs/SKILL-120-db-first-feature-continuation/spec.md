---
status: Complete
---

# SKILL-120 - DB-first feature continuation

Created: 2026-07-12
Issue key: SKILL-120
Mode: single_spec

## Intended Outcome

Make a new invocation of `bill-feature` continue the already-started feature
workflow for the same issue and repository instead of starting a fresh
assessment, pre-planning, and planning sequence.

The durable workflow database is the sole authority for continuation state and
phase artifacts. `preplan_digest` and `plan` remain durable workflow artifacts
for crash recovery and auditability, but a completed `plan` is the canonical
planning input for implementation continuation. Recover `preplan_digest` only
when planning itself must resume, the plan is missing or invalid, or a normal
workflow loop returns to planning. Neither artifact is copied into `spec.md` as
a second mutable source of truth. `spec.md` remains the governed implementation
contract and a useful human-readable input, not the workflow checkpoint store.

## Motivation

`bill-feature` currently sees an existing governed `spec.md`, bypasses feature
spec preparation, and dispatches to `bill-feature-task`. The task router then
opens a new workflow unless a caller already knows and supplies its workflow
ID. A user who returns with only the issue key therefore pays for pre-planning
and planning again, even though the earlier workflow has already persisted
those artifacts and has a valid continuation point.

The database already records normalized issue keys, workflow mode, phase/step
state, and artifacts. It needs a typed, repository-scoped continuation lookup
that the `bill-feature` router can use before normal direct dispatch.

## Desired Behaviour

When the user invokes `bill-feature` with `SKILL-120` (or another issue key),
the router first resolves the current repository and performs a read-only,
typed lookup for matching feature-task work. It then follows this decision
table:

| Lookup result | Router behaviour |
| --- | --- |
| Exactly one resumable workflow | Present the existing single confirmation gate as a continuation confirmation, then resume that workflow at its durable next step. Once `plan` is complete, implementation receives the plan and not the pre-planning digest; completed `preplan` and `plan` steps are skipped. |
| A workflow is already running | Report its workflow ID, mode, current step, and liveness/status. After the existing confirmation gate, continuation takes over that same workflow ID: terminate an exactly identified live worker, or atomically reclaim an orphaned running row when no matching worker exists, then rerun only the incomplete phase. |
| No non-terminal workflow | Preserve today’s normal spec-preparation/direct-dispatch behaviour. |
| More than one eligible workflow | Loud-fail with the candidate IDs and concise state summaries; require an explicit workflow selection rather than choosing “latest”. |
| Only terminal workflows | Report the terminal result and do not silently start a replacement workflow. A new implementation attempt requires explicit restart/new-run intent. |

The same continuation experience must work for both `mode=prose` and
`mode=runtime`. A persisted mode remains pinned on resume; an explicit
conflicting mode request fails clearly instead of silently changing execution
semantics. Code-review selection, agent/model overrides, and other resume-safe
options retain their existing pinning/validation behaviour.

## Acceptance Criteria

1. Feature-task persistence records enough immutable execution identity at
   workflow creation to safely find a run by normalized issue key and current
   repository identity, including the governed spec path needed for resume.
   Repository identity must prevent equal issue keys in different repositories
   sharing one Skill Bill database from being treated as the same work.
2. The runtime exposes one typed, read-only feature-task continuation lookup
   through the supported agent-facing surface(s). It accepts an issue key and
   repository identity, validates durable records at the normal read seam, and
   returns a discriminated result for: no match, exactly one resumable match,
   already-running match, ambiguous eligible matches, and terminal-only
   matches. The result includes only the bounded data needed for routing:
   workflow ID, mode, status, current step/phase, persisted spec path, and a
   concise recovery or terminal summary.
3. `bill-feature` performs the continuation lookup after validating its
   arguments and before feature-spec preparation or direct dispatch. It uses a
   resumable result to resume the identified workflow rather than opening a
   replacement workflow, so a launch after completed `preplan` and `plan`
   continues at `implement` (or the actual durable next step) without running
   those phases again. The resumed implementation receives the completed
   `plan` as its planning context; it does not receive or depend on
   `preplan_digest`.
4. Prose-mode continuation reuses the existing workflow continuation contract:
   it keeps the existing workflow/session IDs, treats recovered artifacts as
   authoritative, and does not reopen the workflow or reconstruct planning
   context from chat history.
5. Runtime-mode continuation invokes the existing runtime resume path with the
   matching workflow ID, issue key, persisted spec path, and current agent
   context. The runtime skips already completed durable phases and preserves
   its existing crash-safe phase-ledger and remediation-loop semantics.
6. An invocation against an already-running workflow never starts another
   process or replays a phase. It returns a clear status/liveness response and
   the workflow ID needed to inspect or recover the running work.
7. If several non-terminal/resumable workflows match the same issue and
   repository, the router never selects the newest one implicitly. It reports
   each candidate’s ID, mode, status, current step, and update time, then
   requires an explicit workflow-ID selection or operator remediation.
8. If matching workflows are terminal only, `bill-feature` reports their
   terminal state and does not create a new workflow or rerun planning unless
   the user explicitly requests a restart/new implementation attempt. Restart
   policy itself is not introduced by this feature.
9. Mode safety holds on continuation: the persisted workflow mode is used for
   resume, and a conflicting explicit `mode:prose`/`mode:runtime` request
   loud-fails before an agent is launched. Existing pinning of code-review and
   other persisted execution choices remains intact.
10. Existing no-match behaviour remains unchanged: a new issue still prepares
    its governed spec and follows the existing single confirmation gate before
    implementation. Decomposed goals and feature-verify workflows retain their
    current, separate continuation routes unless they deliberately reuse the
    new lookup contract without changing their semantics.
11. `spec.md` is not used as a mutable workflow checkpoint and does not receive
    copied pre-planning or plan results. `preplan_digest`, `plan`, and every
    later phase artifact remain database-backed durable workflow artifacts. A
    completed `plan` is the only planning artifact supplied to implementation
    continuation. `preplan_digest` is retained but recovered only when the
    current step is `preplan` or `plan`, the plan is unavailable/invalid, or a
    normal workflow loop explicitly returns to planning; it is never injected
    into an implementation continuation merely because it exists. All recovery
    remains bounded rather than duplicating artifacts in git-tracked specs.
12. Durable identity/lookup failures fail loudly with typed errors: malformed
    issue keys, missing or conflicting persisted execution identity, corrupt
    workflow snapshots, invalid contract versions, and ambiguous candidates
    must never fall back to starting a new run.
13. Tests cover, at minimum:
    - a prose workflow resumed through `bill-feature` after planning continues
      at `implement` and does not execute `preplan` or `plan` again, with the
      completed plan but no pre-planning digest in its implementation context;
    - the equivalent runtime workflow uses its existing workflow ID and skips
      completed runtime phases;
    - a running workflow cannot be duplicated;
    - no match keeps normal first-run behaviour;
    - repository-scoped lookup does not select an identically keyed workflow
      from another repository;
    - ambiguous candidates loud-fail without choosing one;
    - terminal-only candidates do not silently create a new run;
    - explicit persisted-mode conflicts fail before process launch; and
    - corrupt/missing identity data fails loudly rather than falling through to
      new planning.
14. Update relevant skill content, CLI/MCP help or docs, and
    `runtime-kotlin/ARCHITECTURE.md` so the DB is described as the authoritative
    continuation source and `spec.md` is described as the governed feature
    contract rather than a duplicate planning ledger.
15. Maintainer validation passes:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```
16. The `audit_gap` reconciliation loop has no fixed iteration cap. Every
    schema-valid `gaps_found` verdict re-enters planning and implementation,
    preserving its durable iteration watermark, until a fresh audit reports
    `satisfied` or a separate retryability, policy, or user-action gate blocks
    the run. An audit iteration count is observability data, not a reason to
    permanently block otherwise recoverable work.
17. The `review_fix` remediation loop retains its bounded retry budget, but
    exhausting that budget is not a terminal workflow block. Once the final
    allowed review remediation is consumed, the workflow advances to audit
    with the latest review findings preserved as durable evidence. The cap
    prevents further review-fix retries; it does not prevent later phases from
    running.
18. A confirmed continuation can recover a workflow stranded in `running`
    without abandoning it or opening a replacement workflow. Runtime worker
    ownership is durably identifiable using evidence strong enough to avoid
    PID-reuse and cross-host mistakes. If the exact worker is alive, takeover
    terminates it gracefully and escalates only when required; if no matching
    worker is alive, continuation atomically reclaims the orphaned row. Both
    paths retain the workflow and session IDs, preserve completed phases and
    artifacts, increment/restart only the incomplete phase attempt, and use a
    compare-and-set or lease transition so concurrent callers cannot both
    resume it. Tests cover live-worker takeover, orphan reclaim, PID-reuse or
    ownership mismatch rejection, and concurrent reclaim contention.

## Non-Goals

- Do not duplicate raw pre-planning findings, implementation plans, reviews, or
  other workflow artifacts into `spec.md`.
- Do not treat a raw `spec.md` status or hand-edited plan section as authority
  to skip workflow phases.
- Do not automatically restart terminal workflows or define a restart policy.
- Do not select an ambiguous workflow by timestamp, workflow-ID ordering, or
  another implicit heuristic.
- Do not weaken the existing confirmation gate, workflow validation, mode
  pinning, crash recovery, review, audit, or quality gates.
- Do not merge feature-task, decomposed-goal, and feature-verify workflow
  families into one continuation model.

## Constraints

- Preserve the authored skill-source boundary: change `content.md`, not
  generated `SKILL.md` wrappers, and run `./install.sh` after governed skill or
  renderer changes.
- Make identity and lookup data part of a versioned, typed durable contract;
  malformed or legacy-incompatible durable records must loud-fail at a normal
  read seam in line with the runtime-contract policy.
- Keep lookup read-only. Only the normal, confirmed continuation path may
  reopen/advance a resumable workflow or take over a running workflow.
- Maintain strict repository isolation even when the default local database is
  shared across many repositories.
- Preserve bounded continuation artifacts: router decisions may inspect concise
  lookup data, while phase executors recover only the artifacts they need. A
  completed implementation continuation needs the plan, not the pre-planning
  digest.

## Validation Strategy

- Add domain/application tests for repository-scoped candidate classification,
  mode conflict detection, terminal/running/ambiguous/no-match outcomes, and
  typed failure cases.
- Add SQLite migration/repository tests for execution identity persistence,
  normalization, indexing where appropriate, and strict read-time validation.
- Add CLI/MCP adapter tests for the typed read-only lookup response and no
  accidental state mutation.
- Add feature-router and runtime/prose integration tests proving that a relaunch
  after planning resumes from the durable next step without opening a new
  workflow or repeating planning.
- Run the full maintainer validation suite listed above.

## Runtime Run Analysis

This section records defects and design risks observed while executing this
specification on 2026-07-13. It is diagnostic evidence, not a replacement for
the acceptance criteria above. The second review pass being inline is the
intended governed behavior; the defect is that the runtime can require that
pass while the shared review contract simultaneously declares it ineligible.

### Observed Workflows

The original workflow was `wftr-20260713-165724-s0mj`. Pre-planning and planning
completed, implementation blocked twice and completed on attempt three, and the
first delegated review found seven major issues. After the remediation phase
completed, inline review pass two blocked on attempts two through five. The
workflow was ultimately marked `abandoned` out of band so an explicit
replacement could be opened.

The replacement workflow was `wftr-20260713-181432-ry7i`. Pre-planning,
planning, implementation, and the first delegated review completed. Its first
audit reported `gaps_found`, with 18 of 24 extracted criteria unmet. The first
`audit_gap` edge returned to planning. Implementation then blocked twice and
completed on attempt four. Review and audit did not rerun; instead, the runtime
reused the original audit verdict, fired a second `audit_gap` edge, and later
blocked because the loop cap was exhausted after implementation attempt five
blocked.

No evidence indicated a wall-clock timeout or external process kill. Individual
phase agents returned schema-valid blocked/failed outcomes, and the final stop
was a governed loop-cap decision made from a stale audit result.

### Critical: Stale Audit Reused After Durable Resume

An `audit_gap` backward edge is intended to rerun the complete span from plan
through implement, review, and audit. During one live process,
`recordBackwardEdge` calls `reopenForReentry` for that span, but the reopened
completion state is only held in memory. It is not durably represented for all
downstream phases.

If implementation blocks and the process exits during that reentry, resume
reconstructs review and audit from their previous durable `completed` records.
After implementation eventually completes, the run loop skips review and audit
as already complete and reads the previous `gaps_found` verdict. This happened
in the replacement workflow and incorrectly consumed the second `audit_gap`
edge.

Consequences include skipped review after new code changes, acceptance fixes
never being reevaluated, obsolete gaps consuming loop budget, and the workflow
reaching the wrong terminal decision from otherwise valid durable state.

The durable model should record the active backward-edge iteration for every
phase in its reopened span. A phase should count as completed only for the
current loop iteration. Regression coverage must terminate the process after an
`audit_gap` plan completes and implementation blocks, then resume, complete
implementation, and prove that review and audit both execute again before
another edge decision is possible.

### Major: Mandatory Inline Re-review Can Be Unsatisfiable

The feature-task contract correctly requires the second review pass to run
inline and limits its scope to the remediation delta. The shared code-review
contract, however, permits inline execution only for small, low-risk changes
and rejects schema, persistence, migration, public API, concurrency, lifecycle,
and similar high-risk signals.

The SKILL-120 remediation necessarily changed schema, persistence, lookup APIs,
and workflow lifecycle behavior. Its original pass-two scope also incorrectly
included the entire branch. Commit `5a890b6b` corrected that scope to the
remediation delta, approximately 10 files and 204 changed lines, but the pass
remained ineligible because of the high-risk signals. The result was a governed
deadlock: feature-task required inline execution while code-review required
delegated execution for the same diff.

The contracts need an explicit remediation-review policy, typed escalation, or
dedicated bounded inline contract. Repeating an unchanged eligibility decision
must not consume generic agent attempts.

### Major: Runtime Session Identity Collides Across Runs

Both feature-task workflow records had empty `session_id` values. Runtime
telemetry contained only the deterministic session `ftr-SKILL-120`, representing
the beginning and first block of the original workflow. Later resumes and the
replacement workflow had no distinct lifecycle session.

The CLI derives the session identifier from only the issue key. Multiple
workflows for the same issue therefore collide at the telemetry table's session
primary key, breaking workflow-to-session reconciliation and hiding replacement
activity. Session identity should be derived from and persisted with the unique
workflow ID, then reused only for resumes of that workflow.

### Major: Acceptance-Criteria Hierarchy Is Flattened

This specification has 15 numbered top-level acceptance criteria. Criterion 13
is a container with nine nested test requirements. Runtime extraction produced
24 independently scored criteria by counting the parent and all nine children.

The initial `18 of 24` result therefore did not mean that 75 percent of the
feature was absent. Many gaps were individual test bullets, the container was
also counted, and the result later became stale. The implementation was still
materially incomplete, but the flattened denominator exaggerated and obscured
the shape of the incompleteness.

Criteria extraction should preserve hierarchy. A container criterion should
either be excluded from independent scoring or derive its status from its
children. Audit output should report top-level and subcriterion totals
separately.

### Major: A Run Can Install a Runtime That Cannot Resume It

The original workflow predated the new mandatory execution-identity record.
After its implementation installed the updated local runtime, the next resume
loud-failed with `InvalidFeatureTaskExecutionIdentitySchemaError` because that
live workflow lacked identity data. Recovery required a manual SQLite insertion
after taking a backup.

Loud failure for incompatible durable state is correct, but a self-hosted
workflow must not silently replace its executing runtime with a version that
cannot read the workflow. A supported strategy is required: pin the runtime
hash for the workflow lifetime, prevent contract-breaking activation while
affected workflows are active, stage activation until termination, or provide
an explicit operator migration command whose immutable identity inputs are
never guessed.

### Major: No Supported Abandon-and-Replace Operation

The original blocked workflow had to become terminal before a replacement for
the same issue and repository could be selected without ambiguity. No supported
feature-task command could abandon it, so the row was changed directly in
SQLite.

An explicit `feature-task abandon --workflow-id ... --reason ...` operation
should validate that the workflow is nonterminal, append lifecycle evidence,
preserve its ledger, and permit deliberate replacement without selecting a
workflow implicitly by issue key.

### Moderate: Cap Exhaustion Reports the Wrong Phase

After implementation attempt five blocked, durable state identified
`implement` as the current phase. The following resume evaluated the exhausted
`audit_gap` cap while traversing the earlier plan phase and reported plan as the
last incomplete phase, with no resolved branch. This masked the actual blocked
phase and discarded branch information already available in durable state.

Loop-cap checks should operate on fully reconstructed workflow state and retain
the durable current phase, resolved branch, loop endpoints, last failure, and
verdict provenance.

### Moderate: Deterministic Policy Failures Are Retried

The original workflow attempted inline review pass two four times with the same
eligibility result. Without a changed diff or policy, retrying could not
succeed. Phase failures need typed retry semantics such as `retryable`,
`non_retryable_policy_conflict`, `needs_user_action`, `process_failure`, and
`invalid_output`. An unchanged non-retryable policy failure should re-block
without launching another agent or consuming attempt budget.

### Probable Scope Leakage: Unrelated SKILL-124 Specification

During the SKILL-120 remediation sequence, the worktree acquired an untracked
decomposed specification at
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/`, including eight
subtask specifications and a decomposition manifest. It is unrelated to this
feature. The durable phase ledger does not retain file-level authorship, so the
responsible invocation cannot be proven, but its appearance is consistent with
agent scope leakage.

Feature phases should record their before/after file manifests and reject the
creation of a different issue-keyed governed spec unless the active spec
explicitly authorizes follow-up-spec generation.

### Why Implementation Could Complete With Large Audit Gaps

Implementation completion currently proves only that the phase returned a
schema-valid completed outcome. It does not require criterion-level evidence.
Code review then concentrates on defects and risk, while the later audit checks
specification completeness. This layering allowed implementation and review to
complete before the audit discovered missing mandatory identity behavior,
strict lookup validation, workflow-ID propagation, liveness and atomic running
claims, terminal-only resume handling, prose regressions, typed failures, and
much of the requested test matrix.

Implementation output should include a criterion-to-evidence map containing
status, affected symbols/files, and validating tests. A phase should not declare
full completion while criteria remain unknown or unmet; it should return a
typed partial outcome instead.

### Recovery Evidence

The following database backups were created before the two out-of-band recovery
operations:

```text
~/.skill-bill/review-metrics.db.before-skill-120-identity-20260713-200216.bak
~/.skill-bill/review-metrics.db.before-skill-120-restart-20260713-201424.bak
```

The first preceded manual repair of the original workflow's immutable execution
identity. The second preceded marking that workflow abandoned so an explicit
replacement could be opened.

### Recommended Fix Order

1. Fix durable `audit_gap` reentry reconstruction and add process-boundary
   regression tests for every backward-edge span.
2. Resolve the mandatory-inline/high-risk eligibility contradiction.
3. Make lifecycle session identity workflow-specific and persist its join.
4. Preserve acceptance-criteria hierarchy during extraction and scoring.
5. Protect active workflows from incompatible self-hosted runtime upgrades.
6. Add supported abandon-and-replacement operations.
7. Add typed retryability and correct cap-exhaustion diagnostics.
8. Record phase file manifests and reject unauthorized cross-issue artifacts.

### Remediation Status

The follow-up bug pass implemented all eight items above. In particular, phase
records now retain typed failure disposition plus before/after/introduced file
manifests. Resume relaunches only dispositions whose policy permits retry;
unchanged `non_retryable_policy_conflict` and `needs_user_action` records
re-block without consuming another attempt. Every phase also compares its
changed-path manifest around the agent launch and durably blocks when it
introduces a governed `.feature-specs/` path belonging to another issue. Paths
that were already dirty before the phase are retained as evidence but are not
attributed to that phase, which avoids falsely blaming a run for pre-existing
untracked specifications.

The stale-audit reconstruction defect is release-blocking because it can make
the runtime consume bounded recovery budget and block permanently using an
audit verdict that predates the implementation being judged.

## Next Path

Run bill-feature on .feature-specs/SKILL-120-db-first-feature-continuation/spec.md
