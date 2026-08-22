# SKILL-205 — Audit-gap progress or pause

## Intended Outcome

The `audit_gap` remediation loop stops thrashing. When a round makes no
measurable progress on unmet acceptance criteria, or when the loop crosses the
existing warning threshold, the run pauses for an operator decision instead of
re-entering implement indefinitely. When remediation does continue, the next
audit and implement sessions carry durable memory of prior unmet criteria and
what the last implement claim closed, so re-judgment is grounded rather than a
blank-slate re-litigation of the whole AC set.

Multi-loop remediation remains allowed when the unmet set shrinks. Perfect
one-shot closure is not required. Unbounded 30+ iteration spirals are not.

## Background

`audit_gap` is declared uncapped (`perEdgeCap = null`) with
`warnAfterIterations = 3` (SKILL-157). Crossing the threshold emits one
advisory and continues. The runtime already hands implement the **full** unmet
list and instructs it to close every listed criterion in one invocation; the
next audit re-reads the tree and decides every criterion again.

That design tolerates stubborn real gaps. It also tolerates implement partial
fixes, audit judgment churn, and side-effect regressions with no stop
condition. Observed on SKILL-204 subtask 1: `fix_iterations: 1:audit_gap=34`
while the status bar showed multi-digit audit loops. Review remediation already
has a non-convergence pause pattern
(`pauseOnReviewRemediationNonConvergence`); audit_gap has no equivalent.

Operational model overrides (`--phase-model`) help but are out of band. This
feature owns runtime policy and memory so thrash fails closed without relying
on the operator to notice a warning.

## Decisions

1. **No-progress pause.** After an `audit_gap` edge would re-enter implement,
   compare the new unmet criterion set to the previous round's. If there is no
   progress (set did not shrink under a stable identity; same sticky ids /
   equivalent notes per the implementation's declared comparison), pause for
   operator decision instead of recording another live edge. Mirror the review
   non-convergence idea; do not invent a second unrelated pause API if the
   existing operator-decision seam can absorb it.
2. **Warn threshold becomes control flow.** Crossing `warnAfterIterations`
   (today 3 → entering iteration 4) pauses for an operator instead of only
   emitting an advisory. Keep the advisory if useful; it must not be the sole
   effect. Operator decisions reuse the existing grant vocabulary where it
   fits (`retry_fix` to allow another bounded attempt, `accept_and_advance` /
   `abandon_subtask` only where those already apply to this pause class — or
   extend them explicitly if audit-gap pause needs a distinct allowlist).
3. **Gap memory on continue.** Every continuing `audit_gap` re-entry (and the
   audit that follows a remediation implement) receives a bounded projection of:
   prior unmet criteria (ids + notes), which of those the last implement
   receipt claimed to address, and which remain sticky across rounds. Audit
   must re-justify any criterion it repeats; implement must prioritize sticky
   unmet items. Blank-slate "decide every criterion as if first pass" wording
   is replaced or qualified so memory is authoritative context, not optional
   color.
4. **Progress definition is runtime-owned.** Comparison uses durable audit
   outputs / ledger fields, not agent free text. Shrinking the unmet set
   counts as progress even if new criteria appear, as long as at least one
   prior sticky id cleared; pure substitution with equal or larger sticky
   cardinality without clears is no-progress (align with
   `findingCoverageBlockReason`'s "no progress" spirit).
5. **Ops guidance only for models.** Document that stronger `--phase-model`
   for `audit` / `implement` during remediation is recommended; do not hard-code
   model ids into the runtime.

## Acceptance Criteria

1. An `audit_gap` re-entry that would repeat with no progress on the unmet
   criterion set pauses the subtask for an operator decision and does not
   launch another implement session for that edge.
2. Entering `audit_gap` iteration `warnAfterIterations + 1` pauses for an
   operator decision (not advisory-only). Resume after an explicit operator
   grant can take one further remediation attempt; repeated no-progress or
   another threshold crossing pauses again.
3. Continuing remediation briefings (implement under `audit_gap`, and the
   subsequent audit) include a bounded prior-gap memory: previous unmet
   refs/notes, last implement claims against them, and sticky ids. Audit
   prompts require re-justification for repeated criteria; implement prompts
   require prioritizing sticky unmet items.
4. Status / watch / IDE current-phase execution still name the audit loop
   count honestly; a paused run surfaces a blocked/paused reason that names
   no-progress or warn-threshold, not a generic output-gate failure.
5. Focused tests cover: shrinking unmet set continues; identical sticky set
   pauses; warn-threshold pause; operator retry allows one more attempt;
   memory appears on the remediation briefing; advisory-only path is gone for
   the threshold crossing.
6. `./gradlew check --continue` passes for the change.

## Non-Goals

- Capping `audit_gap` to a fixed small max that always hard-blocks without an
  operator seam (pause + decision is the bound).
- Changing `review_fix` topology, repair ledger, or disturbed_remedies rules.
- Trusting implement receipts as proof that criteria are met (audit still
  re-reads the tree).
- Hard-coding model or effort overrides in the runtime.
- Rewriting acceptance-criteria authoring guidance as a separate skill change
  beyond brief prompt notes tied to gap memory.

## Constraints

- Reuse existing operator-decision / pause machinery where possible; extend
  rather than fork a parallel grant system.
- Semantic loops stay free of a silent hard `perEdgeCap` that blocks without
  operator vocabulary — this feature replaces thrash with pause, not with
  silent exhaustion.
- Schema / contract bumps for any new durable gap-memory projection follow the
  usual loud-fail + parity-test path.
- In-flight runs without memory fields degrade safely (empty memory, policy
  still applies once two comparable audits exist).

## Out of Scope Follow-ups

- Auto-accept of sticky minors after N pauses.
- Parent-goal aggregate audit-gap budgets across subtasks.
- Machine-checked AC verifiers that replace the audit agent.
