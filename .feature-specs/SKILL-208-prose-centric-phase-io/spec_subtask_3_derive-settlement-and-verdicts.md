# SKILL-208 subtask 3 — Derive settlement and verdicts

## Scope

Make the runtime decide settlement and routing without depending on the agent
producing a field, so a phase that reports its outcome in prose advances or takes
its remediation edge. The JSON envelope is still emitted after this subtask; what
changes is that no routing or settlement decision reads a required field.

Settlement and routing are contracted separately, because they are not the same
risk. A misread routing verdict costs a remediation round. A misread settlement
advances a run past unfinished work or stops one that finished, and it also
decides retryability: `failure_disposition` currently selects between a durable
non-retryable block and a bounded relaunch, and 20 recorded blocked sessions were
the agent legitimately self-reporting a terminal outcome with a reason an
operator needed. Settlement therefore gets its own reader contract, its own
tests, and a fail-safe default (R4): indecision blocks, never advances, and never
resolves to `completed`.

Derivation stays inside R2. For each signal the runtime already holds the
candidate set, so every read is a membership test rather than natural-language
parsing:

- settlement: the three-value status enum plus the five-value failure
  disposition;
- `audit`: satisfied vs. gaps_found, combined with the criterion refs the run
  invariants declared;
- `verify_findings`: findings_verified vs. no_findings_verified, combined with the
  carried finding ids the runtime delivered in the briefing;
- `review`: approved vs. changes_requested, combined with the finding set subtask
  2 moved to the runtime's own import;
- plan-obligation closure: the delivered executable-plan task ids and carried
  repair item ids.

That last one is what keeps the SKILL-150 truthful-completion gate alive. The
gate exists because a receipt can be schema-valid, say `completed`, and omit half
the plan. The repository cannot say which plan task was done, so
`featureTaskRuntimeImplementationCompletionReason` is re-based on membership: an
obligation is closed when its id appears in the phase's returned text, and an id
that does not appear stays open. That fails safe in the same direction it does
today — the gate blocks and the continuation prompt names the still-open
obligations.

In scope:

- One runtime-owned reader exposing two separately contracted seams: settlement
  (status plus disposition) and the three routing verdicts.
- The run loop, `FeatureTaskRuntimePhaseRecorder`, `FeatureTaskRuntimeRunState`,
  and the audit and validation gate seams consume derived signals through that
  reader instead of reading `verdict`, `produced_outputs.gaps`, and
  `finding_dispositions` directly.
- `featureTaskRuntimeImplementationCompletionReason` and
  `featureTaskRuntimeClosedRepairItemIds` decide closure by id membership over the
  runtime-delivered obligation set, keeping the existing canonicalization so an
  uppercase `AC-` echo still counts.
- Demotion of the schema constraints subtask 1 deliberately left standing:
  `audit`'s required `verdict` and `gaps`, and `verify_findings`'s required
  `verdict` and `finding_dispositions`. Contract version bumped and pinned.
- The indecisive path: one bounded re-ask with a narrowed `requestedAction` over
  the same input, then a durable block. The re-ask has its own budget, distinct
  from the output-gate correction budget and the semantic fix loop, and a re-ask
  interrupted by a crash resumes as a fresh re-ask rather than counting twice.
- Prompt directives for the verifying phases ask for the verdict, the obligation
  ids, and the finding ids in plain terms and stop threatening a schema failure
  for prose.

Out of scope: the `input` / `requestedAction` launch envelope and verbatim prose
results (subtask 4), the facts subtask 2 moved, and deleting the retired schemas
and repair passes (subtask 5).

## Acceptance Criteria

1. One runtime-owned reader derives settlement and the three routing verdicts,
   and the existing entry gates and `audit_gap` / `review_fix` backward edges
   route from that reader with no topology change.
2. Settlement is a separately contracted seam from routing, with its own tests,
   and it derives both the status and the retry disposition.
3. A verifying phase whose output states its verdict only in prose — no `verdict`
   field, no `gaps` array, no `finding_dispositions` array — advances or takes its
   remediation edge instead of failing the phase gate.
4. An indecisive settlement never resolves to `completed` and never advances; an
   indecisive routing verdict never resolves to the advancing verdict.
5. Plan-obligation closure is decided by membership of the runtime-delivered plan
   task ids and carried repair item ids in the phase's returned text; a phase
   claiming completion while an obligation id is unmatched still fails, and the
   continuation prompt names the same still-open set the gate refused on.
6. Derivation is non-destructive: the recorded phase output is unchanged by what
   was derived from it.
7. An indecisive derivation re-asks exactly once with a narrowed
   `requestedAction` over the same input, under a budget distinct from the
   output-gate and semantic fix-loop budgets; a still-indecisive second answer
   blocks durably naming the phase, keeps both outputs, records which one is
   authoritative on resume, and emits a record for the re-ask and for the block.
8. A re-ask interrupted before it settles resumes as one re-ask rather than
   consuming the budget twice.
9. Where a structured signal is still present it agrees with the prose or the
   structured value wins; the resolution rule is stated once in the reader,
   asserted by a test, and marked transitional because subtask 5 removes the
   fields it arbitrates.
10. The demoted schema constraints let prose-only verifying output validate, the
    bumped contract version is pinned by its parity test, and legacy durable
    records are rejected loudly rather than reinterpreted.
11. Verifying-phase prompt directives ask for the verdict and the relevant ids
    without asserting that prose fails the gate.

## Non-Goals

- Changing how phase input is composed or making phase output a verbatim string
  (subtask 4).
- Re-deciding which facts the runtime mints (subtask 2).
- Deriving anything from an open vocabulary. A commit subject, a file path, or a
  severity for a finding the runtime never imported is out of scope here by R2.
- Deleting the phase-output schema, structural repair, or the planning projection
  contracts (subtask 5).
- Adding a second LLM pass, or more than the one bounded re-ask.
- Changing the phase DAG, loop caps, or ceremony scaling.

## Dependency Notes

- Depends on: subtask 2. A review verdict cannot be derived without a `findings`
  array until the finding set comes from the runtime's own import.
- Independent of: subtask 1, though landing after it means the payload rules are
  already diagnostics and these are the only constraints left to demote.
- Unblocks: subtask 4, which removes the typed envelope those fields lived in.

## Validation Strategy

- Targeted tests in `runtime-application` and `runtime-domain`: a prose-only
  `gaps_found` routes `audit_gap`; a prose-only `findings_verified` routes
  `review_fix`; a prose-only `changes_requested` reopens remediation; a
  conflicting field-versus-prose pair resolves by the stated rule; an indecisive
  verdict re-asks once and then blocks with both outputs kept; an indecisive
  settlement blocks rather than completing.
- A completion-gate test with one plan task id present in the prose and one
  absent: the phase fails and the continuation names the absent id.
- A resume test that a crash during the re-ask does not exhaust the re-ask budget.
- Contract-version parity test for the demoted schema.
- Compile the affected runtime modules.

## Next Path

Subtask 4 replaces the envelope itself with the prose I/O pair now that routing,
settlement, and obligation closure no longer depend on producer-supplied fields.
