# SKILL-131 Single-Pass Audit Repair

Status: ready

## Intended Outcome

The feature-task completeness audit produces a complete, structured repair plan for every unmet acceptance criterion. Audit-gap remediation consumes that plan, attempts every repair item in one implementation pass, and cannot claim completion until every item is fixed or proven already satisfied with concrete evidence.

A subsequent audit verifies the repaired repository state. Repeated audit/repair iterations remain available for genuinely new or incorrectly diagnosed gaps, but partial execution, deferred work, lossy free-form handoff, and inconsistent phase-output parsing can no longer create avoidable five- or six-pass convergence loops.

## Motivation

The live SKILL-128 goal-child workflow exposed four related weaknesses:

- audit reported acceptance-criterion gaps but did not create an executable repair plan;
- remediation received only the latest free-form gap messages and could report `completed` while explicitly deferring integration or test work to later phases;
- broad criteria were repaired piecemeal because the runtime persisted no stable gap identity, ordered actions, required evidence, or cumulative resolution state; and
- a Markdown-prefixed audit result containing a valid `gaps_found` JSON envelope passed schema validation but was reparsed differently during transition selection, causing the runtime to advance to validation with an unmet criterion.

The audit loop is a correctness gate, not an iterative task-discovery mechanism. One audit should describe the full known repair scope, and one remediation pass should exhaust that scope before the workflow is allowed to re-review or re-audit.

## Scope

- Define a strict, versioned audit-repair-plan contract carried by `gaps_found` audit output.
- Require one or more actionable repair items for every unmet acceptance criterion.
- Give every gap and repair item a stable identity suitable for durable state, resume, reconciliation, telemetry, and regression detection.
- Persist the accepted repair plan and its execution state in the feature-task workflow artifacts.
- Hydrate audit-gap implementation from the immutable initial preplan and plan plus the complete latest repair plan.
- Require remediation to attempt every repair item and emit structured per-item outcomes and evidence.
- Prevent review and re-audit from starting while any carried repair item is deferred, unattempted, or unresolved.
- Reconcile subsequent audit results against the prior repair plan, closing resolved gaps and distinguishing genuinely new gaps from recurring gaps.
- Parse phase output once and reuse the same normalized, schema-validated object for verification gates, verdict selection, persistence, transition selection, and downstream handoff.
- Preserve standalone feature-task and goal-child parity, crash-safe resume, review behavior, workflow status, telemetry, and generated skill/install boundaries.

## Acceptance Criteria

1. An audit returning `gaps_found` emits a schema-valid `audit_repair_plan` containing every unmet acceptance criterion reported by that audit; a non-empty unmet-criteria signal without a complete repair plan fails loudly and cannot trigger remediation or advance the workflow.
2. Each audit gap has a stable `gap_id`, acceptance-criterion reference and text, concrete failure evidence, diagnosed root cause or bounded investigation action, affected boundary, and one or more ordered repair items.
3. Each repair item has a stable `repair_item_id`, intended repository outcome, explicit implementation actions, affected paths or symbols when known, required verification, dependency ordering, and an initial `pending` status. Free-form prose alone is not a valid repair plan.
4. The runtime validates that every unmet criterion maps to at least one repair item, every repair item belongs to a declared gap, identifiers are unique, dependencies are valid and acyclic, required fields are nonblank, and no reported unmet criterion is omitted or duplicated.
5. The accepted audit-repair plan is persisted durably before the `audit_gap` backward edge is taken. Resume reuses the exact persisted plan and stable identifiers rather than asking a new agent to reconstruct it from prose.
6. Audit-gap remediation receives the immutable initial preplan and implementation plan, the current working tree, and the complete persisted audit-repair plan. It does not regenerate general planning, silently narrow the plan to a subset of messages, or omit previously unresolved items.
7. The remediation agent processes repair items in dependency order and attempts every runnable item during the same implementation phase invocation. It may treat an already-satisfied item as a no-op only when it records concrete repository and verification evidence.
8. Remediation output contains one structured `repair_item_result` for every carried repair item, using the same identifier and a terminal outcome of `fixed` or `already_satisfied`, together with changed paths or symbols, executed verification, and result evidence.
9. A remediation output with a missing item, `pending`, `deferred`, `unattempted`, or unresolved outcome cannot report `completed`, cannot advance to review, and cannot discard that item. A genuinely unresolvable item blocks loudly with its gap and repair-item identifiers and preserves resumable state.
10. The mutating reconciliation gate verifies exact set equality between incoming repair-item identifiers and emitted terminal results. A generic `reconciled: true` flag is insufficient for audit-gap remediation.
11. Remediation instructions and output validation reject statements that assign carried repair work to later review, audit, or validation phases. Those phases may discover new defects but are not substitutes for executing the accepted repair plan.
12. The following audit receives the prior repair plan and execution results and reports each prior `gap_id` as resolved or recurring. A recurring gap retains its identity and includes evidence explaining why the attempted repair did not satisfy it; a genuinely new gap receives a new identity.
13. Durable workflow artifacts maintain a cumulative unresolved-gap ledger across audit iterations. Latest-iteration handoff may add evidence or actions but cannot lose, rename, or silently close a previously unresolved gap.
14. The audit loop records first-pass convergence, recurring-gap count, new-gap count, attempted and resolved repair-item counts, and audit-gap iteration count in status artifacts and telemetry without persisting prompts, source bodies, diffs, or raw tool output.
15. Repeated equivalent gap sets with no repository change or no newly resolved repair items are detected as non-progress and block loudly with actionable evidence instead of looping indefinitely. Genuinely progressing audit repair remains able to continue without an arbitrary correctness-skipping cap.
16. Phase output is normalized exactly once by the contract validator. Verification-signal gates, `gaps_found` verdict derivation, transition selection, persisted structured output, and downstream handoff all consume that same normalized object rather than independently reparsing raw agent text.
17. Markdown commentary, fenced JSON, trailing prose, and bare JSON representations of the same valid audit envelope produce the same verdict and backward transition. A valid `gaps_found` envelope can never default to `ADVANCE` because its raw text is not a bare JSON object.
18. Invalid or ambiguous phase output, multiple conflicting candidate envelopes, a `gaps_found` verdict without gaps, gaps without a repair plan, and a `satisfied` verdict with unresolved durable gaps fail loudly through typed runtime-consistent errors.
19. Standalone feature-task and goal-child workflows use the same audit-plan, remediation-completeness, resume, recurrence, and canonical-output-parsing behavior.
20. Existing review-fix behavior remains independent. Review may identify implementation defects, but review pass exhaustion or carried-forward review results cannot erase or satisfy audit repair items without the required repair evidence and subsequent audit reconciliation.
21. Crash and resume tests prove that interruption before plan persistence, during one repair item, after all repairs but before phase completion, and after audit completion cannot duplicate fixes, lose unresolved gaps, mint different identifiers, or advance past an unmet criterion.
22. A regression fixture based on SKILL-128 proves that an audit with multiple broad unmet criteria creates one complete repair plan; remediation cannot complete after fixing only production code while deferring integration and tests; and the next audit either satisfies all criteria or identifies only genuinely new or recurring gaps with stable identities.
23. A regression fixture reproduces the SKILL-128 Markdown-prefixed `gaps_found` output and proves the runtime takes the `audit_gap` backward edge rather than starting validation.
24. Governed runtime skill guidance and operator-facing documentation describe audit as structured repair planning plus verification, remediation as exhaustive plan execution, the meaning of recurring/new gaps, and the non-progress failure behavior.
25. Contract, domain, application, persistence, CLI/status, telemetry, standalone, goal-child, and end-to-end tests cover acceptance and rejection paths, followed by the repository validation gates:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- The audit phase remains read-only with respect to repository implementation files; it creates and persists workflow repair-plan state only through runtime-owned boundaries.
- The workflow database remains authoritative for repair plans, item results, unresolved gaps, iteration state, and resume behavior.
- Audit remediation continues to reuse the original persisted preplan and implementation plan established by SKILL-126.
- Stable identifiers must be deterministic from durable audit context or persisted exactly once; resume must never depend on model-generated renaming.
- Runtime agent differences remain behind injectable strategies; do not add agent-identity branching to the process runner.
- New or changed YAML runtime contracts follow the repository schema/version/parity/typed-error/classpath-copy recipe and loud-fail incompatible durable records.
- Persist compact structured evidence and counters only. Do not persist prompts, full diffs, source bodies, complete test logs, or raw agent transcripts in the repair ledger or telemetry.
- Preserve manifest-driven platform behavior, code-review selection semantics, goal-child review baselines, and unrelated working-tree changes.
- Update authored `content.md` sources rather than generated skill wrappers, and run `./install.sh` after governed skill, renderer, or support-pointer changes.

## Non-Goals

- Guaranteeing that an agent can fix an unknowable or externally blocked issue in one pass.
- Combining audit and repository mutation into the same agent phase.
- Regenerating the original feature implementation plan during audit remediation.
- Allowing audit to weaken acceptance criteria or mark a gap resolved solely from remediation claims.
- Treating validation as the place to finish repair items intentionally deferred by remediation.
- Removing subsequent audit verification after repair.
- Silently advancing after a fixed number of audit iterations.
- Changing feature decomposition, goal scheduling, review severity, or PR behavior beyond the audit-repair integration points.

## Validation Strategy

- Add schema and contract-version parity tests for valid and invalid audit repair plans, result sets, identifiers, dependency graphs, and criterion coverage.
- Add domain tests for stable gap reconciliation, new-versus-recurring classification, exhaustive item-result matching, non-progress detection, and transition selection.
- Add application tests for plan persistence, exhaustive single-pass execution, blocked repair, resume at each durability seam, and prevention of deferred or partial completion.
- Add phase-output parser tests proving every supported wrapper form returns one canonical object and drives the same audit verdict.
- Add standalone and goal-child integration tests covering review composition, repeated audits, durable status, and telemetry.
- Add SKILL-128-derived end-to-end fixtures for partial repair rejection and Markdown-prefixed `gaps_found` transition safety.
- Run focused tests during implementation, then the complete repository validation gates listed in acceptance criterion 25.

## Next Path

Run bill-feature on `.feature-specs/SKILL-131-single-pass-audit-repair/spec.md`.
