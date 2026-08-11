# SKILL-182 · Subtask 3 — Reconcile the delegated-review docs with the runtime and the Cursor section

## Scope

`docs/delegated-review/` is the largest surface that contradicts subtask 1. It
documents the SKILL-145 external-process delegated subsystem that SKILL-159 deleted,
and a reader landing there today gets a different — and wrong — answer about Cursor
than the delegation contract gives.

Current state, verified file by file:

- `decision.md` already carries a `> **Superseded by SKILL-159.**` preface. That preface
  is itself stale in one respect: it states that `auto` resolves to delegated on the
  first pass and inline on follow-up passes. The live contract
  (`orchestration/skill-classes/code-review-shell.yaml`, `orchestration/review-delegation/PLAYBOOK.md`)
  says `auto` resolves to `inline` for every pass and for a scope with no pass number,
  and `delegated` is reached only by explicit selection. Correct the preface.
- `provider-capability-matrix.md` has **no** preface and reads as current. It presents
  Cursor as `experimental` with 8/8 capabilities and names
  `DelegatedReviewProviderCapabilityRegistry` as "the executable source for this table".
  That type was deleted by SKILL-159. It also describes per-provider stream decoders,
  process strategies, and a coordinator-owned capacity plan — all removed. This is the
  file most likely to be read as a Cursor support claim, so it needs the same
  historical-record treatment.
- `reliability-contract.md`, `provider-failure-dispositions.md`, `lifecycle-evidence.md`,
  and `failure-matrix.md` describe the same removed subsystem (provider CLIs as review
  workers, canary sampling, promotion gates, worker processes, waves). `failure-matrix.md`
  is explicitly framed as a historical ledger; the other three are not.

Bring every file in the directory to one consistent framing: each states, at the top,
that it is the historical record of a removed subsystem and points to
`orchestration/review-delegation/PLAYBOOK.md` as the current contract. Do not rewrite
the historical bodies — the removal-preface pattern is the approach already recorded as
reusable in `orchestration/review-orchestrator/agent/history.md`, and rewriting bodies
would destroy the design record without adding accuracy.

Then record Cursor's *current* support position where a reader will actually look for
it. The delegation playbook is the contract; this subtask adds no second contract. What
it adds is a statement of tier: Cursor delegated review is experimental and explicit
opt-in, launched in-harness like Claude and Codex, and not verified end-to-end against a
live Cursor CLI. Do not present Cursor as equal to the harnesses that have been verified
in practice.

Also sweep for any other surface that would now contradict the playbook:

- `docs/capabilities.md` — check its review section for a claim about which agents can
  run delegated review.
- `docs/review-telemetry.md` — its "Bounded delegated-review accounting" section
  describes lane accounting; confirm it does not assume harness-returned lane ids, which
  subtask 1 makes optional.
- `skills/bill-code-review-parallel/content.md:28` ("Supported agents: claude, codex,
  cursor, copilot, junie") — this is the parallel second-lane mechanism, not delegation.
  Leave it alone unless it makes a delegation claim.

Do not change the playbook itself here; subtask 1 owns it.

## Acceptance Criteria

1. Every file under `docs/delegated-review/` opens with a statement that it is the
   historical record of the subsystem SKILL-159 removed, and links to
   `orchestration/review-delegation/PLAYBOOK.md` as the current contract.
2. No file under `docs/delegated-review/` presents `DelegatedReviewProviderCapabilityRegistry`,
   or any other type deleted by SKILL-159, as a current executable source without that
   historical framing.
3. The `decision.md` preface describes the live mode resolution: `auto` resolves to
   `inline` for every pass and for a scope with no pass number, and `delegated` is
   reached only by explicit selection.
4. `provider-capability-matrix.md` no longer reads as a current statement of which
   providers support delegated review.
5. Cursor's current delegated support is stated once as experimental, explicit opt-in,
   in-harness, and not end-to-end verified against a live Cursor CLI, and it does not
   contradict the Cursor section added in subtask 1.
6. `docs/capabilities.md` and `docs/review-telemetry.md` contain no claim that
   contradicts the delegation playbook after subtask 1 — in particular, no claim that
   Cursor cannot run delegated review, and no lane-accounting rule that requires a
   harness-returned launch id.
7. The historical bodies of the SKILL-145 documents are otherwise unchanged; only
   framing, the corrected preface, and contradicting claims are edited.
8. `scripts/validate_agent_configs` and `npx agnix --strict .` pass, and every
   intra-repo link touched in this subtask resolves.

## Non-Goals

- Rewriting or deleting the SKILL-145 historical design records.
- Editing `orchestration/review-delegation/PLAYBOOK.md` (subtask 1) or native-agent
  rendering (subtask 2).
- Promoting Cursor to a verified support tier, or claiming parity with Claude and Codex.
- Reintroducing capability-matrix vocabulary as a live contract, or adding a new
  provider capability registry.
- Changing the parallel-review supported-agents list.

## Dependencies

Subtask 1 (the Cursor harness section is the contract these docs must agree with) and
subtask 2 (the rendered Cursor agent shape is what the support statement describes).

## Validation Strategy

- Grep `docs/` for the deleted type names from
  `.feature-specs/done/SKILL-159-review-mode-restructure/spec_subtask_1_remove_external_delegated_subsystem.md`
  and confirm every surviving mention sits under a historical-record framing.
- Grep `docs/`, `skills/`, and `orchestration/` for `cursor` case-insensitively and read
  each hit against the new playbook section; list any hit deliberately left unchanged
  and why.
- Verify the corrected `auto` description against
  `orchestration/skill-classes/code-review-shell.yaml` rather than against memory — that
  YAML is the executable statement of mode resolution.
- Check every markdown link touched in this subtask resolves to an existing path.
- Run `scripts/validate_agent_configs` and `npx agnix --strict .`.

## Next Path

Feature complete. Record the Cursor delegated-review decision in the review-orchestrator
area decision log if the contract change warrants a boundary-decision entry.
