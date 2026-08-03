# SKILL-145 delegated review decision

**Decision:** delegated review is not supportable as a default. Codex, Claude,
and Cursor remain experimental, explicit opt-in providers. Junie, Copilot,
Opencode, and Zcode remain unsupported. Inline is the default, and `auto`
continues to resolve to inline until a separate governed change approves a
provider promotion.

## Evidence basis

This decision uses the consolidated [`failure-matrix.md`](failure-matrix.md),
the lifecycle reproductions in [`lifecycle-evidence.md`](lifecycle-evidence.md),
the provider capability matrix in
[`provider-capability-matrix.md`](provider-capability-matrix.md), and the
provider-keyed dispositions in
[`provider-failure-dispositions.md`](provider-failure-dispositions.md).
The historical ledger contains items 1–47 exactly once and gives every
remaining item an owner, evidence status, bounded reference, and rationale.
The current runtime evidence is deterministic and bounded; it is not a claim
that one successful run proves promotion.

## Provider classification

| Provider | Classification | Evidence | Promotion or rejection condition |
|---|---|---|---|
| Codex | experimental explicit opt-in | `DelegatedReviewProviderCapabilityRegistry`; Codex command builder and native lifecycle callbacks; lifecycle and capacity fixtures | Keep experimental until independent authenticated canaries satisfy every promotion gate in the reliability contract. A missing durable progress, deadline, aggregation, or isolation result rejects promotion. |
| Claude | experimental explicit opt-in | Claude command builder, streamed decoder, callback strategy, and provider matrix | Keep experimental until Claude-specific canaries satisfy the same gates without importing Codex behavior. |
| Cursor | experimental explicit opt-in | Cursor command builder, independent stream decoder, callback strategy, and provider matrix | Keep experimental until Cursor-specific canaries satisfy the same gates without importing another provider's strategy. |
| Junie | unsupported | Capability registry marks lifecycle and terminal-result support absent; launch path returns explicit unsupported outcome | Reject until an independent adapter exposes fresh isolation, tracking, output, progress, cancellation, timeout, tokens, terminal result, and aggregation evidence. |
| Copilot | unsupported | No registered delegated worker adapter in the capability registry | Reject until a governed adapter and provider-isolation test suite exist. Never substitute inline execution. |
| Opencode | unsupported | Runtime-refused provider set and capability registry | Reject until runtime support is independently governed and its terminal outcome is harvestable. |
| Zcode | unsupported | Runtime-refused provider set and capability registry | Reject until runtime support is independently governed and its terminal outcome is harvestable. |

The classifications are independent. Shared coordinator accounting,
schema validation, or inline policy does not turn an unsupported provider into
an experimental one and does not promote an experimental provider.

## Promotion criteria

Each experimental provider must provide a bounded, authenticated canary set
covering small, medium, and multi-area reviews. Promotion is falsifiable only
when all checks pass for that provider:

- p95 elapsed time stays within the approved size-specific latency budget;
- every declared area is completed exactly once, with no silent drop or
  duplicate ownership;
- every selected worker has a durable terminal status, including loss,
  cancellation, interruption, and timeout;
- every run has one deterministic terminal classification;
- diagnostic references and retained evidence stay within contract bounds; and
- provider-isolation tests prove that changing this adapter cannot alter
  Claude, Cursor, Codex, or any unchanged adapter.

The current evidence satisfies the shape and rejection guards but does not
satisfy the independent measured promotion gate. Therefore no provider is
classified as supportable.

## Reliability contract

The enforceable lifecycle boundary is
[`reliability-contract.md`](reliability-contract.md), version 0.1. It is
separate from the immutable packet and assignment projection. It defines
durable identity and progress, coordinator/worker capacity and waves, startup,
progress-idle, per-worker, aggregation, and whole-review deadlines, terminal
failure classes, complete aggregation, retry idempotency, cancellation
reconciliation, provider isolation, and bounded diagnostics. Incomplete
results cannot enter successful aggregation or schema repair.

The existing `review-context` 0.8 packet/assignment contract and the current
runtime review models are retained as already-covered scope surfaces. They
remain authoritative and are not duplicated or weakened by this decision.

## Historical ledger result

Items 1–7, 12, and 20 are verified as existing SKILL-144/SKILL-146 coverage;
they are explicitly excluded from remediation in this issue. The remaining
rows are either verified by current lifecycle/provider evidence or deferred to
the ordered follow-up specifications below. No row is unresolved without an
owner, evidence status, and rationale.

## Ordered follow-up implementation specifications

These are preparation artifacts, not implementation of the remediation tracks:

1. [`spec_followup_schema.md`](spec_followup_schema.md) — versioned lifecycle,
   deadlines, capacity, terminal status, aggregation, and bounded diagnostics.
2. [`spec_followup_domain.md`](spec_followup_domain.md) — worker states,
   transitions, waves, deadlines, retries, cancellation, and aggregation.
3. [`spec_followup_application.md`](spec_followup_application.md) — durable
   coordinator, worker launch, progress, watchdog, recovery, and aggregation.
4. [`spec_followup_persistence.md`](spec_followup_persistence.md) — lifecycle
   rows, event history, uniqueness, migrations, recovery, and reconciliation.
5. [`spec_followup_provider-adapters.md`](spec_followup_provider-adapters.md) —
   independent provider capabilities and strategy isolation.
6. [`spec_followup_telemetry.md`](spec_followup_telemetry.md) — bounded
   lifecycle events, measurements, and provider-observation rules.
7. [`spec_followup_installation.md`](spec_followup_installation.md) — installed
   provider identity and native-agent capability validation.
8. [`spec_followup_documentation.md`](spec_followup_documentation.md) — policy,
   rationale, ownership, and evidence-retention documentation.
9. [`spec_followup_tests.md`](spec_followup_tests.md) — executable acceptance,
   rejection, isolation, and end-to-end aggregation coverage.

The order is a dependency order: schema precedes domain, domain precedes
application and persistence, those precede provider and telemetry integration,
installation identity, documentation, and the combined test suite.

## Explicit exclusions

This decision does not rebuild routing, generic fallback, least-context
handoffs, packet/assignment scope, flattened composition, native-agent
inventory, installer commands, install refresh, or inline review behavior.
Those surfaces are verified as existing SKILL-144/SKILL-146 contracts. This
subtask also introduces no new decomposition manifest, workflow, generated
artifact, provider-native output, or install refresh.
