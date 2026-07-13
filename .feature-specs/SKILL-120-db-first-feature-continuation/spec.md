---
status: Ready
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
| A workflow is already running | Report its workflow ID, mode, current step, and liveness/status; do not launch a competing run or replay any phase. |
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
  reopen/advance a resumable workflow.
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

## Next Path

Run bill-feature on .feature-specs/SKILL-120-db-first-feature-continuation/spec.md
