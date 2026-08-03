# Follow-up specification: lifecycle and reliability schemas

**Order:** 1 of 9  
**Depends on:** the current packet/assignment contract and this decision  
**Purpose:** make the lifecycle envelope versioned, strict, loud-failing, and
separate from immutable review-context contents.

## Scope and targets

- `orchestration/contracts/review-lifecycle-schema.yaml`
- `orchestration/contracts/review-context-schema.yaml` (reference only; packet
  and assignment scope remains authoritative)
- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/review`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/contracts/review`

The implementation must add or preserve explicit envelopes for lifecycle
identity, worker state, attempt, provider capability, coordinator slot,
predicted and actual waves, all five deadline scopes, terminal class,
aggregation completeness, retry/cancellation reconciliation, and bounded
diagnostic reference. Packet and assignment schema changes are prohibited
unless a separate governed context-contract change approves them.

## Acceptance and rejection cases

Accept a current contract version with digest-bound packet, assignment,
provider, worker, and attempt identity; positive timestamps; unique event and
assignment ids; complete wave accounting; positive deadline limits; valid
terminal transitions; exact aggregation coverage; and diagnostics within the
declared byte and character limits.

Reject stale or unknown versions, missing identity, malformed digests or
timestamps, duplicate worker/area ownership, zero or negative capacity,
invalid wave membership, unknown deadline scope, impossible transition,
incomplete aggregation, stale retry attempt, mismatched provider, and
oversized or raw-content diagnostics. Reject prompt, complete-diff,
source-body, transcript, and tool-log fields through strict additional-property
validation.

## Contract and parity work

The schema must declare its `contract_version` and schema id. Kotlin exposes a
matching version constant and classpath/repository paths, a typed invalid-schema
exception, and one shared validation function used at both producer and
consumer seams. A configuration-cache-friendly resource copy task must guard
missing schema resources at execution time. Schema version drift, malformed
fixtures, and consumer repair attempts fail loudly; they do not silently
coerce legacy lifecycle records.

## Exclusions

Do not conflate lifecycle state with packet contents. Do not change SKILL-144 or
SKILL-146 routing, fallback, assignment, least-context, or installation
contracts. Do not persist prompts, full diffs, source bodies, raw transcripts,
or tool logs.
