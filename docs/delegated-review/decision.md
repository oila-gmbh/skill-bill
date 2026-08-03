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
The bounded provider measurements are records PM-001 through PM-007 in this
decision; each record reports the provider's capability count, launch or
refusal outcome, and current promotion-gate result using the capability matrix
and runtime registry as its measurement sources.
The historical ledger contains items 1–47 exactly once and gives every
remaining item an owner, evidence status, bounded reference, and rationale.
The current runtime evidence is deterministic and bounded; it is not a claim
that one successful run proves promotion.

## Bounded provider measurements

These records are bounded repository measurements from the provider capability
matrix, the provider registry, and the launch/refusal boundary. `8/8` means all
eight capability dimensions are present; `0/8` means the provider has no
delegated capability at that boundary. `0/6` promotion gates means no
independent authenticated canary has satisfied the six gates in the reliability
contract. These are provider-specific observations, not inferred parity with
another provider and not a claim that a live canary has run.

| Record | Provider | Measurement source | Measured outcome | Promotion/rejection result |
| --- | --- | --- | --- | --- |
| PM-001 | Codex | `DelegatedReviewProviderCapabilityRegistry` and Codex adapter surface | `8/8` capabilities present; delegated launch is available; terminal disposition is `experimental explicit opt-in` | `0/6` promotion gates satisfied; promotion remains rejected pending authenticated small, medium, and multi-area canaries |
| PM-002 | Claude | `DelegatedReviewProviderCapabilityRegistry` and Claude adapter surface | `8/8` capabilities present; delegated launch is available; terminal disposition is `experimental explicit opt-in` | `0/6` promotion gates satisfied; promotion remains rejected pending Claude-specific authenticated canaries |
| PM-003 | Cursor | `DelegatedReviewProviderCapabilityRegistry` and Cursor adapter surface | `8/8` capabilities present; delegated launch is available; terminal disposition is `experimental explicit opt-in` | `0/6` promotion gates satisfied; promotion remains rejected pending Cursor-specific authenticated canaries |
| PM-004 | Junie | Capability registry and unsupported launch boundary | `0/8` capabilities present; delegated launch is unavailable; terminal disposition is `blocked_unsupported` | `0/6` promotion gates satisfied; unsupported rejection is satisfied and no inline substitution is permitted |
| PM-005 | Copilot | Capability registry lookup and adapter inventory | `0/8` capabilities present; no delegated adapter is registered; terminal disposition is `blocked_unsupported` | `0/6` promotion gates satisfied; unsupported rejection is satisfied and no inline substitution is permitted |
| PM-006 | Opencode | `RUNTIME_REFUSED_AGENTS` and refusal boundary | `0/8` capabilities present; launch is refused; terminal disposition is `blocked_unsupported` | `0/6` promotion gates satisfied; runtime-refusal rejection is satisfied and no inline substitution is permitted |
| PM-007 | Zcode | `RUNTIME_REFUSED_AGENTS` and refusal boundary | `0/8` capabilities present; launch is refused; terminal disposition is `blocked_unsupported` | `0/6` promotion gates satisfied; runtime-refusal rejection is satisfied and no inline substitution is permitted |

The records retain only provider, capability count, launch disposition, and
promotion outcome. They do not retain prompts, diffs, transcripts, source
bodies, or tool logs. The later validation phase may add authenticated canary
measurements; until then, a missing canary sample is a failed promotion gate.
The canary protocol requires at least 20 launched runs per provider and size
class (60 per provider) in one 30-consecutive-UTC-day window, uses nearest-rank
p95, and includes every failed or timed-out run in both the sample count and
the failed promotion result. The PM records are not canary samples and one run
cannot promote any provider.

## Provider classification

| Provider | Classification | Measured evidence | Historical ledger | Promotion or rejection condition |
|---|---|---|---|---|
| Codex | experimental explicit opt-in | [`PM-001`](decision.md#bounded-provider-measurements) measures `8/8` capabilities and an available delegated launch. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` satisfied because no authenticated canary set has been recorded; missing durable progress, deadline, aggregation, or isolation evidence rejects promotion. |
| Claude | experimental explicit opt-in | [`PM-002`](decision.md#bounded-provider-measurements) measures `8/8` capabilities and an available delegated launch. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` satisfied because no Claude-specific authenticated canary set has been recorded; cross-provider evidence cannot promote Claude. |
| Cursor | experimental explicit opt-in | [`PM-003`](decision.md#bounded-provider-measurements) measures `8/8` capabilities and an available delegated launch. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` satisfied because no Cursor-specific authenticated canary set has been recorded; cross-provider evidence cannot promote Cursor. |
| Junie | unsupported | [`PM-004`](decision.md#bounded-provider-measurements) measures `0/8` capabilities, unavailable launch, and `blocked_unsupported`. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` promotion gates; explicit unsupported rejection is satisfied and no inline substitution is permitted. |
| Copilot | unsupported | [`PM-005`](decision.md#bounded-provider-measurements) measures `0/8` capabilities, no registered adapter, and `blocked_unsupported`. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` promotion gates; explicit unsupported rejection is satisfied and no inline substitution is permitted. |
| Opencode | unsupported | [`PM-006`](decision.md#bounded-provider-measurements) measures `0/8` capabilities, refused launch, and `blocked_unsupported`. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` promotion gates; runtime-refusal rejection is satisfied and no inline substitution is permitted. |
| Zcode | unsupported | [`PM-007`](decision.md#bounded-provider-measurements) measures `0/8` capabilities, refused launch, and `blocked_unsupported`. | [Items 1–47](failure-matrix.md) are the complete ledger. | [`G-001`–`G-006`](reliability-contract.md#promotion-gate): `0/6` promotion gates; runtime-refusal rejection is satisfied and no inline substitution is permitted. |

The classifications are independent. Shared coordinator accounting,
schema validation, or inline policy does not turn an unsupported provider into
an experimental one and does not promote an experimental provider.

## Promotion criteria

Each experimental provider must provide a bounded, authenticated canary set
covering small, medium, and multi-area reviews. Before evaluating the six
gates, the provider must have at least 20 launched runs in each size class
(60 provider-specific runs) collected within one 30-consecutive-UTC-day
window. p95 uses the nearest-rank estimator over every launched terminal
sample; failed, timed-out, cancelled, interrupted, and blocked samples remain
in the denominator, cannot be replaced, and fail promotion. Promotion is
falsifiable only when all checks pass for that provider:

- p95 elapsed time is at most 120 seconds for small reviews (1–2 declared
  areas), 300 seconds for medium reviews (3–5 declared areas), and 600 seconds
  for multi-area reviews (6 or more declared areas);
- every declared area is completed exactly once, with no silent drop or
  duplicate ownership;
- every selected worker has a durable terminal status, including loss,
  cancellation, interruption, and timeout;
- every run has one deterministic terminal classification;
- each review retains at most 256 lifecycle evidence events and 1,048,576
  aggregate UTF-8 evidence bytes, with a 30-day maximum age after terminal
  persistence; individual references and summaries remain within schema bounds;
- provider-isolation tests prove that changing this adapter cannot alter
  Claude, Cursor, Codex, or any unchanged adapter.

The current PM-001–PM-007 measurements satisfy the classification shape and
unsupported-provider rejection guards, but no provider has a canary sample
that satisfies the independent measured promotion gate. Therefore no
provider is classified as supportable.

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
