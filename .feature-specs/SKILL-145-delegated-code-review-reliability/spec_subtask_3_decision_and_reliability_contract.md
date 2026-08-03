# SKILL-145 Subtask 3: Provider-aware decision and reliability contract

## Scope

Synthesize the lifecycle and provider evidence into a go/no-go decision for
explicit delegated review. Define the smallest enforceable contract for any
provider that can be promoted beyond experimental opt-in, and separate
approved remediation into follow-up implementation specs. Preserve inline as
the default and preserve the existing review-area checklist.

## Acceptance Criteria

1. The decision classifies delegated review independently for Codex, Claude, Cursor, and every other claimed provider as supportable, experimental explicit opt-in, or unsupported.
2. Every classification cites measured evidence, the complete historical failure matrix, and the failed or satisfied promotion criteria.
3. The reliability contract defines durable worker identity and progress, packet/assignment scope identity, capacity and wave accounting, startup/progress/per-worker/aggregation/whole-review deadlines, terminal failure classes, aggregation completeness, retry idempotency, cancellation reconciliation, and bounded diagnostic evidence.
4. Promotion criteria are falsifiable and include bounded latency, complete declared-area coverage, zero silent worker loss, deterministic terminal status, bounded evidence retention, and provider-isolation tests.
5. Recommended implementation work is split into ordered schema, domain, application, persistence, provider-adapter, telemetry, installation, documentation, and test changes, with already-covered SKILL-144/146 surfaces explicitly excluded.
6. The decision explicitly preserves inline as the default and keeps `auto` inline until a separate governed change approves promotion.
7. The final report contains no unresolved historical item without an owner, evidence status, and rationale.

## Non-Goals

- Implementing the entire remediation plan in SKILL-145.
- Declaring reliability from a single successful run.
- Changing inline review coverage, severity, or output semantics.
- Persisting full prompts, diffs, source bodies, or raw provider transcripts.

## Dependency Notes

Depends on subtasks 1 and 2. The decision must not be written before their
evidence package and provider matrix are available.

## Validation Strategy

- Trace every conclusion to a fixture, durable record, bounded diagnostic reference, or measured canary.
- Review the proposed contract against current runtime schemas, persistence seams, and provider strategy boundaries.
- Validate that every proposed test has acceptance and rejection coverage and that follow-up work is separately executable.

## Next Path

Use the decision to prepare separate governed implementation specs for approved reliability remediation; leave delegated mode explicit opt-in until then.

## Final Criterion Trace

| Criterion | Decision/report evidence | Follow-up or existing contract |
|---|---|---|
| AC-001 | `decision.md` classifies Codex, Claude, Cursor, Junie, Copilot, Opencode, and Zcode independently. | `provider-capability-matrix.md`; `spec_followup_provider-adapters.md` |
| AC-002 | `failure-matrix.md` is the exact 1–47 ledger; every provider row cites its PM-001–PM-007 measured outcome, the ledger, and the reliability promotion result. | `lifecycle-evidence.md`; `provider-capability-matrix.md`; `provider-failure-dispositions.md` |
| AC-003 | `reliability-contract.md` defines identity, progress, scope, capacity, waves, five deadlines, terminal classes, aggregation, retry, cancellation, isolation, and diagnostics. | Existing `review-lifecycle-schema.yaml` and runtime review models; `spec_followup_schema.md` through `spec_followup_persistence.md` |
| AC-004 | `decision.md` and `reliability-contract.md` record falsifiable latency limits of 120/300/600 seconds by review size, 256-event/1,048,576-byte/30-day retention limits, coverage, worker-loss, terminal-status, and isolation gates. | `spec_followup_schema.md`; `spec_followup_telemetry.md`; `spec_followup_provider-adapters.md`; `spec_followup_tests.md` |
| AC-005 | `decision.md` orders schema, domain, application, persistence, provider adapter, telemetry, installation, documentation, and test specifications. | `spec_followup_*.md`; SKILL-144/146 exclusions are explicit. |
| AC-006 | `decision.md`, both review playbooks, and telemetry documentation preserve inline default and `auto`-inline behavior. | Existing review execution-mode contract. |
| AC-007 | Every `remaining` ledger row has an owner, evidence status, bounded reference, and rationale; no unresolved row is ownerless. | `failure-matrix.md`; `spec_followup_documentation.md` |

The subtask remains one direct child-subtask plan. It adds no remediation
implementation, new decomposition manifest, workflow, generated artifact,
provider-native output, or install refresh. All referenced paths resolve within
the repository or to the existing SKILL-144/SKILL-146 contract names.
