# SKILL-181 — A parent-spec edit must never force shared-preplan regeneration

## Intended Outcome

Amending a governed parent spec mid-goal never blocks the run and never requires an operator to
discard planning state. The shared preplan is either reused, or transparently refreshed in-run, and
sibling plan rows for subtasks that already produced commits are never discarded as collateral.

`replan --include-shared-preplan` survives as a deliberate escape hatch, not as the routine remedy
for having edited a spec.

## Evidence: the WE-4719 run, 2026-08-10

Observed in repository `capmo-android`, goal `WE-4719`, parent workflow `wftr-20260810-135336-1isx`,
runtime `0.2.1-SNAPSHOT`.

Sequence, in order:

1. **Launch.** Two-subtask goal, `code-review-mode inline`, `--agent claude --no-live-output`.
2. **Operator pause.** `goal pause WE-4719` → `requested / operator_request`. The runtime honoured
   it at the subtask boundary. Subtask 1 completed first: child workflow
   `wftr-20260810-140736-v1ct`, commit `0f0932e1c8cbe378c8de63afe925e1a7763d8eb2`,
   `last_resumable_step=commit_push`.
   - Terminal line: `goal WE-4719: paused at subtask 1 — Goal paused at a durable boundary: operator_request`
   - Process exit code: **1**
   - Status after: `complete=1 pending=1 current_subtask=2 current_step=pending_launch`,
     `execution_liveness=idle`, `planning: state=prepared shared_preplan=true planned=2/2`
3. **Spec amendment.** Both the parent spec and subtask 2's spec were edited (rewrote parent AC 8,
   added a new parent AC, renumbered `./gradlew check` from 9 to 10, added a Background item, and
   rewrote subtask 2's scope, acceptance criteria, and validation strategy). Body content genuinely
   changed.
4. **First replan.** `goal replan WE-4719 --subtask 2` →
   `discarded_plan: subtask=2; existed=true`, `preserved: shared_preplan=true`,
   `planned_before=1,2`, `planned_after=1`. Subtask 1's `commit_sha` and `workflow_id` preserved.
5. **Relaunch → hard stop.**
   - `goal WE-4719: blocked at subtask 0 — Goal planning preparation cannot be recovered because the
     current governed parent spec or immutable decomposition provenance differs from the saved
     shared preplan.`
   - Process exit code: **1**
   - Status after: `blocked=0`, `execution_liveness=idle`,
     `planning: state=partially_planned shared_preplan=true planned=1/2 current=2`,
     `planning_reason: Saved plans will be reused; planning can resume at subtask 2.`
   - Note the incoherence: the status projection reported planning as resumable while the launch
     path had just refused to resume it.
6. **Second replan.** `goal replan WE-4719 --subtask 2 --include-shared-preplan` →
   `discarded_plan: subtask=2; existed=false`, `discarded_shared_preplan: true; cascaded_plans=1`,
   `preserved: shared_preplan=false`, `planned_before=1`, `planned_after=` (empty). Runtime state
   again preserved: subtask 1 still `complete` at `0f0932e1c`.
   - The single cascaded plan row was **subtask 1's** — a subtask already `complete` with a pushed
     commit. Discarding its planning row bought nothing and cost its planning provenance.
7. **Relaunch.** Proceeded, regenerating the shared preplan from the amended parent spec.

Cost: one wasted launch, two replan invocations, one regenerated preplan (a model call), one
discarded terminal plan row. No durable work was lost — runtime fields and acceptances survived both
replans exactly as documented.

## Why regeneration was required

Not because preplanning's inputs changed in any way that mattered. Because the recoverability gate is
an equality check on the parent spec, and it fails closed.

`GoalPlanningSweep.recoverableProvenance`
(`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalPlanningSweep.kt:188-204`)
reuses the saved preplan only when all of the following hold:

- `decompositionManifestHash` matches current
- `phaseOutputContractId` matches current
- the saved `parent_spec` in the planning packet is self-consistent with its recorded
  `parentSpecHash` (`sha256HexUtf8`)
- `canonical(savedParentSpec) == canonical(currentParentSpec)`

That last clause is the binding one. The canonicalization
(`GoalPlanningSharedContextPacket.kt:360-389`) normalizes exactly one thing: it strips a `status:`
line from YAML frontmatter, collapsing the frontmatter block if that empties it. It does **not**
canonicalize the body at all. Its purpose is to tolerate a Linear-mode spec whose frontmatter status
moves — nothing more.

So any body edit — a reworded acceptance criterion, a renumbered list, an added background item —
makes `canonical(saved) != canonical(current)`, and the sweep calls `incompatibleProvenance`
(`GoalPlanningSweep.kt:206-212`), which returns `Stopped`. The sweep has no path that regenerates the
preplan on mismatch; regeneration is reachable only from the operator CLI.

The deeper problem is the severity classification. A preplan derived from an older spec revision is
not corrupt. Preplanning walks a bounded catalog of governed boundary headings and returns the ids it
judges relevant in `selected_boundary_headings`; the runtime then resolves only those bodies into the
plan phase. Its output is therefore **a selection over a catalog**. A spec edit can make that
selection *under-inclusive* — it might now pick up a heading it previously skipped. It cannot make it
*wrong*: every selected heading is still a real heading, and no durable state depends on the
selection. Stale preplan is a completeness risk, and the runtime currently treats it as unrecoverable
corruption.

## Verified Codebase Facts

Confirmed against `feat/SKILL-180-validate-suppression-gate` (`97c04f4b6`). Re-verify any that a
rebase moves.

- `GoalPlanningSweep.recoverableProvenance` — the four-clause gate above, `:188-204`.
- `GoalPlanningSweep.incompatibleProvenance` returns `Stopped` at phase `PHASE_PREPLAN` with subtask
  id `0`, `:206-212`.
- `currentProvenance` builds `GoalPlanningContractProvenance(parentSpecHash,
  decompositionManifestHash, EXPECTED_SCHEMA_ID)`, `:182-186`.
- Spec canonicalization is frontmatter-`status:`-only, `GoalPlanningSpecCanonicalization`.
- The shared-preplan record carries `provenance`, `payload_sha256`, and an opaque
  `preplan_payload: {type: string, minLength: 1}` —
  `orchestration/contracts/goal-planning-preparation-schema.yaml:70-78`.
- `selected_boundary_headings` is a real projection field with enforced count caps
  (`orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml`), consumed by
  `GoalPlanningContextPromptFormatter` and `FeatureTaskRuntimePhasePromptComposer`.
- `producePlan` receives `sharedCheckpoint.preplanPayload` plus `resolvedBodies`
  (`GoalPlanningSweep.kt:176`), so the plan phase consumes the resolved heading bodies, not the raw
  spec-to-heading judgement.
- `FeatureTaskRuntimePrePlanningDigest` carries model-authored fields
  (`affected_boundaries`, `risks`, `rollout`, `validation_strategy`, …) alongside
  `selected_boundary_headings`; the stored `preplan_payload` is that digest JSON.

## Design

Replace the equality gate with **validity plus refresh**. Three changes, in dependency order, plus a
reporting-truthfulness fix.

### 1. Reuse when the preplan is still valid, not only when the spec is identical

Split the current single gate into two questions:

- **Valid?** `decompositionManifestHash` matches, `phaseOutputContractId` matches, the saved packet
  is self-consistent, the payload matches `payload_sha256`, and every id in
  `selected_boundary_headings` still resolves in the freshly parsed heading catalog. Catalog parsing
  is already programmatic and model-free, so this is cheap.
- **Fresh?** `canonical(saved) == canonical(current)`.

Valid and fresh → reuse silently, as today. Valid but not fresh → go to step 2. Not valid → the
existing loud stop is correct and stays (a manifest change, schema change, or a selected heading that
no longer exists is genuine incompatibility).

### 2. Refresh in-run instead of stopping

When the preplan is valid but stale, the sweep re-runs the preplan phase itself against the current
spec, then compares the new `selected_boundary_headings` to the saved set:

- **Set unchanged** — overwrite the provenance to current, keep the payload, and keep **every**
  sibling plan row including non-terminal ones. Nothing downstream depended on anything that changed.
  This is the expected outcome for an editorial spec amendment and should be the common case.
- **Set changed** — adopt the new preplan (full new payload) and discard only **non-terminal** sibling
  plan rows, per step 3. Grow and shrink are treated the same: any set inequality cascades
  non-terminal plans.

No operator action, no blocked status, no separate CLI invocation. This is what makes the outcome
"preplan never needs regeneration" from the operator's point of view: it is refreshed for them.

### 3. Never cascade a terminal subtask's plan row

A subtask with a terminal status and a `commit_sha` has already consumed its plan; the row is
historical provenance. Discarding it cannot change future work and destroys the record of how a
landed commit was planned. Cascade must skip terminal subtasks, both on automatic refresh and on
explicit `--include-shared-preplan`.

### 4. Truthful terminal classification

Two separate defects observed in the same run:

- The stop message says planning "cannot be recovered" when it demonstrably can, via a documented
  flag. Any surviving stop must name the exact remedy command.
- A durable operator pause exited with code `1`, indistinguishable to a supervising harness from a
  genuine failure; the invoking session reported "failed with exit code 1" for what was a clean,
  requested pause. Paused, blocked, and failed need distinct exit codes.

Also reconcile the status projection with the launch path: step 5 above reported `planning can resume
at subtask 2` while launch refused to resume.

## Resolved Design Decisions

1. **Payload vs heading-set.** `preplan_payload` is the full `preplanning_digest` JSON, including
   model-authored fields. Validity still keys off heading-id resolution (plus hash/schema/manifest
   checks). Freshness refresh re-runs preplan; cascade is gated only on
   `selected_boundary_headings` set equality. When the set is unchanged, keep the saved payload and
   update provenance only — do not treat prose-field drift alone as a cascade trigger.
2. **Shrink vs grow.** Any set inequality (grow or shrink) adopts the new payload and cascades
   non-terminal plan rows. No asymmetric shrink path.

## Acceptance Criteria

1. Editing a governed parent spec's body while a goal is idle mid-run, then relaunching, continues
   the run without an operator command and without a blocked terminal state.
2. A stale-but-valid preplan is refreshed in-run; a preplan whose `selected_boundary_headings` no
   longer resolve, or whose manifest or schema provenance differs, still stops loudly.
3. When a refresh produces an unchanged `selected_boundary_headings` set, every sibling plan row is
   retained — no cascade, terminal or otherwise.
4. When a refresh produces a changed set, only non-terminal sibling plan rows are discarded.
5. No subtask with a terminal status and a `commit_sha` ever has its plan row discarded, by any path,
   including explicit `--include-shared-preplan`.
6. Runtime state — subtask `status`, `commit_sha`, `workflow_id`, and out-of-band acceptances — is
   untouched by refresh, exactly as it is by today's replan.
7. Paused, blocked, and failed goal terminations use distinct, documented process exit codes; a
   durable operator pause is not reported as a failure.
8. Any surviving planning stop names the exact remedy command in its message.
9. The status projection's `planning_reason` cannot claim planning is resumable when the launch path
   would refuse it.
10. `replan --include-shared-preplan` still forces regeneration when invoked explicitly.
11. `./gradlew check` passes.

## Constraints

- Fail-closed stays the default for genuine incompatibility. This narrows what counts as
  incompatible; it does not make the gate permissive.
- Refresh must be atomic against the durable record: a crash mid-refresh leaves either the old valid
  preplan or the new one, never a provenance/payload mismatch.
- Refresh must be refused while the goal is live, on the same liveness rules that already guard
  scoped replan.
- One preplan refresh per launch. Refresh must not become a loop.
- Create `feat/SKILL-181-preplan-provenance-refresh` from the current branch
  `feat/SKILL-178-fix-all-findings-remediation-gate` (not from `main`).

## Non-Goals

- **Broadening body canonicalization** to absorb "cosmetic" edits. It cannot solve this: the
  triggering edit changed real content. Whitespace- or renumbering-tolerant hashing would add a
  brittle heuristic and still fail the next real amendment. The fix is severity classification, not a
  cleverer hash.
- Making the preplan independent of the parent spec. Relevance judgement legitimately depends on spec
  content.
- Removing `--include-shared-preplan`.
- Any change to subtask-plan provenance rules, which behaved correctly throughout the observed run.
- Special-casing shrink-only heading-set changes to avoid cascade.
