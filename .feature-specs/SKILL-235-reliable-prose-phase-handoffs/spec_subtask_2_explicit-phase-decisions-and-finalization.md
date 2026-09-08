# SKILL-235 Subtask 2 - Explicit phase decisions and finalization

Parent spec: [.feature-specs/SKILL-235-reliable-prose-phase-handoffs/spec.md](./spec.md)
Issue key: SKILL-235

## Scope

Complete the communication migration using subtask 1's functioning publication boundary. Audit publishes prose plus an explicit checkpoint-bound pass/gaps operation. Review, verification and implement_fix record prose finding bodies and incremental item dispositions with runtime-owned IDs/census; retain the existing review driver, severity and bounded remediation policies. History and PR publish narrative while typed file/Git/quality receipts come from runtime observations.

Remove remaining requirements for model-authored final envelopes, complete receipt arrays, copied version/digest fields and self-attested reconciliation booleans on the new route. Keep legacy adapters only at declared compatibility seams; their presence must not require the new agent path to serialize JSON. Integrate outcome handling, missing-item recovery, evidence freshness, status and content-free telemetry across all remaining phases in this commit.

## Acceptance Criteria

1. Audit publishes its report through the shared boundary and explicitly selects pass or report-gaps for the evaluated repository/input checkpoint. Missing or ambiguous decisions request decision-only recovery; pass can never be reconstructed from prose, a parser default, exit status, or lack of parsed findings.
2. Review preserves each original prose finding and assigns or maps it once to a stable runtime identity scoped to the review generation/checkpoint. Verification and implement_fix operate on bound item handles or small incremental updates, without requiring a complete model-authored terminal array or a format-sensitive finding register.
3. The runtime computes census against the carried findings and durably acknowledges each valid disposition. Replayed updates are idempotent, invalid/cross-generation/unknown item handles reject specifically, and one rejected item does not erase its valid peers. Ask only about missing or invalid items; unresolved/omitted entries never default to successful repair.
4. Runtime-owned reconciliation and current repository evidence replace model-authored reconciliation booleans and hashes as admission proof. New changes invalidate affected audit/quality/finding clearance by checkpoint or equivalent provenance without silently treating earlier approval as current or discarding reusable immutable planning.
5. Existing audit remediation and one bounded review-fix policy remain intact. End-of-round unresolved work stays explicitly persisted and visible; publication/census repair cannot reset semantic caps or create an unbounded repair loop.
6. History and PR narrative use the shared prose publication/recovery route. Runtime derives file-change receipts and retains authority for selected build/validation command receipts, commit SHA, branch, pushed state, and PR identity. Do not make build compile-proof imply suite validation or re-run irreversible side effects merely to repair a report.
7. All supported phase/provider/routes select a compatible publication protocol before launch. New prompts and governed source content contain no requirement for handwritten final JSON, JSON embedded inside prose, exact Markdown headings, or copied runtime evidence. Legacy records remain explicitly compatible or governed-migrated, and currently owned legacy attempts are not reinterpreted.
8. Public status and content-free telemetry distinguish a pending publication, missing decision/item, semantic unresolved work, real process/store/policy failure, and completed validated work. Recoveries and fallbacks retain their classification instead of AcceptedUnchanged, without emitting prose or arbitrary diagnostic payloads remotely.
9. Regression scenarios cover audit negation/missing decisions, large finding inventories with one omission or malformed update, stale review generations, partial batch persistence/restart, post-audit edits, finalization acknowledgement loss, and history/PR report recovery without duplicate entries/side effects. Preserve subtask 1's prose, fencing, parity and budget guarantees.
10. Update the canonical phase communication documentation and governed prompts/install output together. Document the actual phase graph, compatibility behavior, publication versus semantic retry ownership, and measurement definitions. Use local evidence and replay/fixture results honestly; do not claim a global PostHog improvement without a matching cohort.

## Non-Goals

- Changing the engineering judgment encoded in platform review rubrics or introducing extra approval gates beyond explicit phase decisions.
- Increasing review-fix rounds, rerunning all audits after every edit, changing goal decomposition or automatic Git authorization.
- Repairing unrelated IDE polling or undertaking broad observability/documentation cleanup outside phase IO.

## Dependency Notes

Depends on: 1
Requires subtask 1's integrated publication and ownership contract. This is a separate shippable increment because audit/finding decisions and finalization have distinct semantic/side-effect obligations; it must not replace the working publication service with another phase-specific protocol. Completes parent 6–7, 9 and remaining 10–12 while preserving 1–5 and 8.

## Validation Strategy

Exercise actual workflow admission and consumer handoffs, incremental SQLite item updates, and finalization receipt recovery. Include a report explicitly saying 'not satisfied' without a decision, a partial finding batch with a single invalid item, a delayed old-generation fix, changed repository evidence, and lost acknowledgements after successful side effects. Assert both no formatting-only operator block and no fabricated semantic pass. Verify history/PR generation recovery reuses operation receipts. Follow parent routed validation/build limits, inspect rendered prompts and run ./install.sh when required. Each scenario must test observable outcome or state isolation, not only prompt string equality.

## Next Path

Feature complete when the parent acceptance criteria are met. Let the goal runtime finalize the subtask commit/push and parent PR through its existing policy; report remaining genuine blockers or unexecuted validation truthfully.

## Spec Path

.feature-specs/SKILL-235-reliable-prose-phase-handoffs/spec_subtask_2_explicit-phase-decisions-and-finalization.md
