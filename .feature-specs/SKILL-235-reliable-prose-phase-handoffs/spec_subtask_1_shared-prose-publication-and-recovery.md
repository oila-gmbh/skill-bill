# SKILL-235 Subtask 1 - Shared prose publication and recovery

Parent spec: [.feature-specs/SKILL-235-reliable-prose-phase-handoffs/spec.md](./spec.md)
Issue key: SKILL-235

## Scope

Ship a complete publication path for preplan, plan, and initial implement in standalone and parent-goal execution, including parallel plan slots. Strengthen the existing settlement service and store rather than creating another authority. Wire the producer prompt, runtime-issued attempt context, artifact/text tool and bound CLI transports, admission, persistence, handoff rendering/retrieval, and publication-only recovery in the same commit. Include the immediate audit keyword/default-approval safety fix even while audit remains on an explicit compatibility transport.

Choose the concrete scoped capability mechanism during planning after examining provider launch/MCP/CLI facilities. The acceptance contract is fixed: scope metadata comes from the runtime, ordinary prose is publishable without JSON, and neither transport bypasses active ownership checks. Do not depend on a human or model copying attempt identifiers from a briefing. Provider fixtures must cover every supported agent; live provider availability is recorded, not assumed.

The work is independently shippable when these three prose producers work end-to-end and unmigrated phases are explicitly pinned to their existing protocol. Apply the parent constraints, artifact budget strategy, and privacy rules. Track current responsibilities after SKILL-233 and accommodate SKILL-234's ownership recovery without reopening either feature's unrelated work.

## Acceptance Criteria

1. Preplan, plan, and initial implement accept normal Markdown reports through runtime-serialized publication, with no required final JSON or embedded JSON. Optional hint/null/blank/notes formatting cannot cause an output-schema operator block; ordinary quotes, braces, code blocks, and multiple headings remain content.
2. Standalone preplan/plan and goal shared-preplan/per-subtask-plan routes use the same publication admission component and recovery behavior. Concurrent plan slots cannot collide or adopt each other's artifact, decision, attempt, or input digest.
3. At launch, the runtime binds repository/run/slot/phase/attempt/generation identity and chooses an available text-tool or artifact-plus-CLI transport. Agents supply content or use the assigned artifact and phase-appropriate operation only. Missing capabilities fail preflight; no fallback asks the agent to author envelopes.
4. Settlement verifies the active ownership fence and current attempt before state changes. Identical retry returns the same receipt; stale, cross-slot, unknown, and conflicting publications fail specifically without overwriting accepted content or clearing another owner's result.
5. Crash/lost-acknowledgement recovery preserves one accepted artifact and one effective advancement, with consistent ledger/outbox state. Artifact import is complete before its reference becomes visible. Pending publication does not permit a still-mutating or incompletely observed process to attest a final checkpoint.
6. Publication recovery keeps completed code, saved report and input identity intact, counts actual repairs durably under a distinct bounded budget, and cannot edit/reimplement production work. Resume the provider session where supported; otherwise perform only scoped report/decision recovery and expose that route.
7. Plan receives the accepted preplan prose, implement receives plan prose, and audit can consume the new implementation artifact through an explicit compatibility projection. Hints stay subordinate to consumer instructions and never change routing or permissions.
8. Full prose survives prompt-budget overflow and is retrievable through a consumer/run-scoped handle with visible omission/size information. Invalid handles and incomplete or unavailable storage fail specifically; there is no silent truncation or producer rerun to shorten text.
9. Remove keyword-based audit verdict recovery and any missing-outcome default that could invent approval. A report saying 'not satisfied', a missing verdict, an empty parsed findings list, or normal process exit cannot become audit pass; compatibility failures retain evidence and request only the needed decision.
10. Persist publication protocol/capability metadata and expose typed publication-pending/recovery status plus content-free accepted/recovered/rejected events. Legacy completed outputs remain explicitly consumable or follow governed migration/quarantine; actively owned attempts are never silently switched.
11. Real-validator and SQLite/provider-boundary regression cases establish ordinary prose acceptance, optional hint normalization, parity across standalone/goal slots, duplicate/conflicting/stale publication, acknowledgement loss, restart, pending process reconciliation, and no-work-rerun recovery. This commit includes schemas, ports/adapters, prompts, relevant documentation, and render/install updates needed to make the feature usable.

## Non-Goals

- Migrating every review/finding/finalization producer before the shared prose path is independently usable.
- Changing audit/review remediation caps, quality commands, Git side-effect ownership, or broad module architecture.
- Adding a formatter agent, strict Markdown grammar, arbitrary artifact browsing, or a second ledger.

## Dependency Notes

Depends on: none
No feature-local predecessor. Reconcile the active SKILL-233 refactor and any landed SKILL-234 recovery work before editing overlapping code. Do not implement against historical paths when the responsibility moved. Parent acceptance ownership: 1–5, 8, common 10–12, and the no-inferred-approval portion of 6.

## Validation Strategy

Name the bug each regression catches. Use actual phase admission and handoff paths for raw Markdown and optional metadata; use SQLite for duplicate, conflict, stale-generation and crash-boundary behavior; exercise concurrent parent plan slots with distinct content. Simulate publication after code edits and acknowledgement loss to assert edits are not rerun. Verify the old audit 'not satisfied' counterexample cannot approve. Cover supported provider launch/terminal fixtures and tool-absent CLI publication, including custom database/repository binding. Follow the parent validation ownership constraints and run ./install.sh if source/generated prompts changed; do not claim live-provider coverage from fixtures.

## Next Path

Continue with .feature-specs/SKILL-235-reliable-prose-phase-handoffs/spec_subtask_2_explicit-phase-decisions-and-finalization.md through skill-bill goal SKILL-235. Runtime owns subtask commit/push; do not start the next task manually inside this implementation session.

## Spec Path

.feature-specs/SKILL-235-reliable-prose-phase-handoffs/spec_subtask_1_shared-prose-publication-and-recovery.md
