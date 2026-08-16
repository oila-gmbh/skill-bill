# SKILL-193 — Locator-indexed review parent packet

## Intended Outcome

The review parent packet is an **index**: paths, hunk ids, spans, content
digests, evidence locators, routing, and lane decisions. Hunk bodies live only
in the existing digest-addressed evidence store. Workers fetch assigned hunks
through the broker. Parent-packet size stays independent of branch diff size.

A mechanical split that previously failed the whole review with
`parent_packet_bytes` (SKILL-16 subtask 12: 715555 > 524288) composes
successfully. A lane that cannot fit its assigned bodies within the lane
evidence budget ends **incomplete** and names the unreviewed units. Findings
stay bounded (`file:line`, at most 7 per specialist) and never carry file
bodies.

## Background

`ReviewPreparationService.composePacket` serializes every `ReviewChangedHunk`
into the parent envelope, including `content`.
`ReviewContextBudgetPolicy.maxParentPacketBytes` (default 524288) then measures
`packet.canonicalBytes` and throws `review_context_budget_exceeded` before any
worker launches. Fail-closed is correct: the contract forbids truncating
required evidence, skipping a required lane, or silently changing mode.

The bodies do not belong in that envelope. SKILL-164 already stores the same
bytes under a checkpoint-keyed store path (`diff.patch` plus a file/hunk
index) and forbids inlined diff content on the shared evidence **projection**.
Lane assignments already carry `hunk_ids`, not bodies. Launch bundles then
copy `hunk.content` back in (`ReviewLaneAssembledEntry.toEnvelope`,
`ParallelCodeReviewRunner.appendAssignedBundleEvidence`). The parent packet
duplicates the store and makes every review pay the full delta against one
512 KiB cap.

Findings are not the problem. Specialists already emit at most 7 findings with
`file:line` evidence. Durable review accounting is forbidden from carrying
prompts, complete diffs, transcripts, or tool logs. This feature reorganizes
**review input**, not the finding register.

## Design

### Parent packet is an index

`changed_hunks` on `kind: parent_packet` lose `content`. Each hunk carries
`hunk_id`, path, spans, `content_digest`, and the evidence locator that
addresses the body in the shared store. `hunk_id` remains content-addressed
over path, spans, normalized content, and commit scope; composition computes
it from the store and records the id, it does not put the body on the wire.

An undeclared diff-content carrier on the parent packet, assignment, or
launch bundle is a schema failure, matching the shared-evidence projection
rule.

This is an incompatible review-context contract change. Bump
`REVIEW_CONTEXT_CONTRACT_VERSION` together with
`orchestration/contracts/review-context-schema.yaml`. A 1.0 packet on a 2.0
runtime loud-fails; there is no in-place rewrite of in-flight envelopes.

### Bodies stay in the store; the broker is the read seam

Hunk bodies remain in the SKILL-164 evidence store. Composition does not copy
them into `ReviewContextPacket` or the parent envelope. The broker (already
the specialist evidence seam) is the only path that materializes assigned
hunk bodies, in bounded batches counted against `max_lane_evidence_bytes`.

Launch envelopes project assigned hunk ids, spans, digests, and locators.
They do not embed `content`. Inline and delegated use the same rule: one
inline worker is one lane over the full assignment and still fetches through
the broker.

### Budget split

| Envelope | Counts | On overflow |
| --- | --- | --- |
| Parent packet | Index only (routing, decisions, hunk metadata, locators) | Whole-review `parent_packet_bytes` (rare; index itself too large) |
| Lane launch | Assignment metadata, rubric, locators | `max_lane_launch_bytes`; lane incomplete |
| Brokered evidence | Assigned hunk bodies actually delivered | `max_lane_evidence_bytes`; lane incomplete, names unreviewed hunk ids |

Do not raise `max_parent_packet_bytes` as the fix. Do not truncate required
hunk bodies. Do not skip a required lane. Do not substitute execution mode.

A completed integration pass still must not close an incomplete lane's
coverage gap.

## Scope

- Versioned review-context schema and Kotlin models for index-only hunks.
- Parent-packet and launch-bundle projections that omit hunk bodies.
- Composition that addresses bodies through the shared evidence store.
- Broker delivery of assigned hunks in bounded batches.
- Budget measurement and incomplete-lane coverage for body overflow.
- Conformance tests, including a SKILL-16-scale mechanical-split fixture.
- Contract-version pinning and loud failure of 1.0 packets.

## Acceptance Criteria

1. The parent packet envelope has no hunk `content` field and no other
   property that accepts inlined diff bytes; schema validation rejects one.
2. Each parent-packet hunk carries `hunk_id`, path, spans, `content_digest`,
   and an evidence locator that resolves to the stored body.
3. `hunk_id` stays content-addressed over path, spans, normalized content, and
   commit scope; changing stored body bytes changes the id.
4. `packet.canonicalBytes` / `parent_packet_bytes` does not grow with hunk
   body size. A fixture whose stored patch exceeds `max_parent_packet_bytes`
   still composes when the index fits.
5. Launch envelopes and inline prompt assembly do not inline assigned hunk
   bodies; workers receive locators and fetch bodies through the broker.
6. Brokered hunk bodies count against `max_lane_evidence_bytes`. Overflow
   terminates that lane as incomplete with
   `review_context_budget_exceeded`, naming unreviewed hunk ids and
   `budget_dimension`. It does not block sibling lanes or fail parent
   composition.
7. Required lanes are never dropped to make a packet fit. Truncation of
   required hunk bodies is a contract breach, not a degraded delivery.
8. Findings remain capped at 7 per specialist with `file:line` evidence.
   Finding envelopes, accounting summaries, and goal-facing review output
   still omit hunk bodies and source dumps.
9. `REVIEW_CONTEXT_CONTRACT_VERSION` and the schema bump together. A 1.0
   parent packet, assignment, or launch fails loudly on a 2.0 runtime.
10. Shared evidence store and projection contracts from SKILL-164 remain
    the body authority; this feature does not add a second diff store.
11. SKILL-191 verification and adjudication launches keep cited-region plus
    delta-reference shape; they do not regain inlined parent-packet hunks.
12. Focused tests cover: index-only schema rejection of `content`, parent
    compose success on an oversized patch, lane incomplete on evidence
    overflow, broker serving only assigned hunks, digest mismatch
    loud-fail, and 1.0 envelope rejection. `skill-bill validate` passes.

## Constraints

- Fail-closed: never truncate required evidence, skip a required lane, widen
  repository access, replace a reviewer, or substitute execution mode.
- Hexagonal direction: schema and domain models own the index contract;
  filesystem store and broker stay in adapters; application composes.
- Public wire and component contracts are versioned; incompatible change
  fails loudly.
- Durable projections omit prompts, diffs, source, and tool-output bodies.
- Do not edit SKILL-191 files it is actively validating unless this contract
  change requires it; prefer additive seams.

## Non-Goals

- Raising `max_parent_packet_bytes` (or repo-local overrides) as the solution.
- Reorganizing the finding register, severity vocabulary, or the 7-finding cap.
- Chunking review by module or inventing a second evidence store.
- Changing SKILL-191 claim-verification or spec-adjudication stage semantics.
- Changing v2 (`skill-bill-v2`) review slots.
- Silently completing an incomplete lane via the integration pass.

## Validation Strategy

Schema, domain, preparation, broker, and runner tests as listed in the
subtasks, then `skill-bill validate` at the repository root.
