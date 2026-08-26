# runtime-kotlin/ boundary decisions

This file records architectural and implementation decisions that span the
`runtime-kotlin/` boundary. Each entry is dated and explains the trade-off,
not the implementation detail.

## [2026-08-26] Runtime git commit waits for the target repo's hooks

Context: Atlas `commit_push` died at the runtime's 30s git timeout while `.githooks/pre-commit` ran `./gradlew ktfmtFormat` against a cold Gradle daemon. The implementation work was already on disk.

Decision: `commit` and `push` wait 10 minutes. Other git calls stay at 30s. Hooks still run; the runtime does not pass `--no-verify`.

Reason: The target repo declared those hooks. Skipping them would land unformatted code. Raising every git call would hide hung `status`/`rev-parse`. Ten minutes covers a cold daemon plus format without matching the 120-minute validation-gate budget.

Alternatives considered: `--no-verify` on runtime commits (rejected: bypasses the repo's format gate). A single raised timeout for all git (rejected: plumbing hangs would sit for minutes). No timeout on commit (rejected: a stuck hook would wedge finalisation with no bound).

## [2026-08-25] A process failure stores the child's output instead of a bounded excerpt

Context: WE-4860 subtask 4 blocked four times on `agent exited with non-zero status 1`, each time carrying the child's own claim that the host had slept mid-response. The host's `pmset` log showed no sleep at any of those timestamps. Nothing durable existed to check the claim against: a process failure reaches no output gate, so `rejected_output_diagnostics` and `producer_output_evidence` both stayed empty, and the only surviving trace was the excerpt inlined in the block reason, capped at `STDERR_EXCERPT_MAX_CHARS`. `reconcileLaunch` read `AgentRunLaunchFacts`, which holds both streams in full, and dropped them.

Decision: `LaunchResult.InfraFailure` carries the child's stdout and stderr, and the block seam stores them as a rejected-output diagnostic under rule `process-failure`, retrievable by the existing `feature-task rejected-output --raw-output`. The body is framed: both streams labelled with their lengths, plus exit status, timeout, interruption and spawn flags. Absent output stores nothing rather than an empty row. The write is best-effort and precedes the block.

Reason: A failure whose only evidence is a 200-character excerpt cannot be diagnosed, and this one was actively misleading — the child named a cause the host's own log contradicted. Both streams are kept because a child's diagnosis arrives on whichever it happens to use, and attributing the wrong one is how a false cause gets believed. The write is best-effort because the block is what settles the phase: a lost artifact is a worse diagnosis, while a lost block is a wedged run.

Alternatives considered: Widen the inline excerpt (rejected: the block reason is read by operators and prompts, and a full transport dump does not belong in either). A new table for process failures (rejected: the rejected-output store already has payload retention, sha/size verification, lifecycle and a CLI; a second store would need all of it again and a second command to reach it). Store only stderr (rejected: this failure class printed its message on stdout, so stderr alone would have retained nothing).

## [2026-08-25] A misnamed key's prose is adopted, not discarded and then reported missing

Context: WE-4860 subtask 4 emitted `reconciliation_evidence` as `{"reconciled": true, "notes": "<the evidence>"}`. `notes` is not declared on that closed object, so canonicalization discarded it as an unknown key, and strict validation then rejected the receipt for `required property 'evidence' not found`. Correct, in-cap prose was deleted by the repair layer and then reported absent. The producer cannot learn from that rejection either: from its side it did supply the text.

Decision: before the unknown-key discard, a closed object whose prose field is absent adopts the value of a lone unknown key holding non-blank text, recorded as `misnamed_key_adopted`. Wired per call site — currently `reconciliation_evidence.evidence` — rather than as a general rule.

Reason: The two halves of the closed-object contract were defeating each other: the SKILL-152 class-1 repair manufactured its own class-2 failure. Adoption is information-preserving in the way the class-2 prohibition cares about — nothing is synthesized, the producer's own text is moved to the field it was written for.

Alternatives considered: A general "single unknown key fills the single missing required field" rule (rejected: `deviation` requires `ref` and `note`, so a general rule would file a sentence as an identifier — worse than rejecting; a test pins that adoption never reaches `deviations`). Adopt when several unknown keys are present (rejected: which one is the evidence is a guess, and the earlier `{reconciled, method, observations}` shape must keep rejecting). Truncate over-length adopted prose (rejected: SKILL-169 forbids it, and a length violation is the one class whose correction tells the producer to compress).

## [2026-08-25] Goal liveness falls back to the parent lease when the child lease is idle

Context: SKILL-208's goal runner was live with a fresh parent execution lease,
but status reported `execution_liveness: idle` because the child worker lease
was still the expired implement-phase row. The IDE maps idle to paused, so the
plugin showed paused while the validate agent was running.

Decision: child LIVE or UNKNOWN still wins. Child IDLE (missing or expired
worker lease, or a dead local owner) falls through to the parent goal
execution lease before status reports idle.

Reason: The parent lease is the authority that a goal runner still owns the
goal. Child leases are phase-scoped and routinely lag across resume, runtime-
owned gates, and agent turns. Treating an idle child as global idle hides a
live parent.

Alternatives considered: Require every phase to renew the child lease before
status can be live (rejected: races with runtime-owned work and resume). Change
only the IDE mapping so idle stays active (rejected: idle must still mean no
live runner when both leases are gone).

## [2026-08-25] Validate repair Task must not re-invoke collect-all

Context: SKILL-208 validate sat "live" for hours while the repair agent ran
`./gradlew check --continue | tail -15`. The Task line still ordered
bill-code-check collect-all even after the runtime had projected findings, so
the agent rediscovered the suite and buffered all output until EOF — no fixes.

Decision: when `validationGateFindings` is present, the validate Task is
`validateRepairPhaseTask`: fix the listed set; forbid bill-code-check, pack
collect-all, and `check --continue`; allow only targeted pack-checker tasks.
The findings preamble matches that forbid list. Absent-gate agent-run fallback
keeps the collect-all Task.

Reason: Runtime already owns discovery and post-repair verify. A second full
suite in the repair session duplicates wall clock and, with `tail`, hides
findings from the agent. Build repair already forbade collect-all; validate
must match.

Alternatives considered: Soften only the findings preamble (rejected: Task is
authoritative and still invited collect-all). Intercept shell gradle in the
runner (rejected: prompt contract is the cheaper fix and matches existing
build ownership).

## [2026-08-25] An over-bound review input elides deleted bodies rather than blocking

Context: WE-4860 subtask 3 retires a module. Its review input measured 1,716,726 bytes against the 1,000,000-byte bound, of which 1,114,385 — 65% — was the complete body of 170 deleted files. The bound blocked the subtask, so the 14 added, 238 modified, and 29 renamed files went unreviewed as well.

Decision: when the delta exceeds the bound, the review input keeps every added, modified, and renamed patch in full and replaces each deleted file's body with a manifest line naming the path and the lines it lost. Elision is conditional: a delta that already fits is unchanged, byte for byte. A delta still over the bound after elision keeps its full text so the failure reports the real size. Operator-chosen over raising the bound and over chunking the review.

Reason: A deleted file's body is the part of a retirement delta a reviewer can act on least, and the alternative was reviewing none of it. The reduction is consistent with how this review already works: lanes read bodies on demand through `read_evidence` and reach past their assigned hunks through `request_expansion`, so the manifest's paths are a locator rather than a dead end. Measured at 625,771 bytes on the real delta, 37% under the bound.

Alternatives considered: Raise `GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES` (rejected by the operator: it pushes 1.1MB of deleted source into the review context and moves the wall rather than removing it). Chunk an over-bound delta across passes and merge findings (rejected: most faithful, but it breaks the one-pass-per-delta assumption the findings ledger, coverage gate, and repair receipt are all built on). Elide unconditionally (rejected: an ordinary subtask's review input should not change, and a reviewer should never wonder which shape they are holding). Note the cost accepted here: a block of logic relocated out of a deleted file is easier to miss when only the deletion's path is inline.

## [2026-08-24] An absent summary is filled from the producer's prose, not rejected

Context: WE-4860 subtask 3's implement receipt narrated its work in prose, then emitted a fenced envelope carrying all thirteen closed tasks and no `summary`. The envelope walker requires every declared field before a span can be a candidate, so nothing matched and a 23KB receipt was discarded over the one root field no consumer branches on — `terminalBlockedReasonFrom` reads it as `.orEmpty()`, and the runtime already authors its own for gate-executed phases.

Decision: `PhaseOutputExpectedShape.withRecoveredSummary` fills an absent `summary` from the last paragraph of the producer's prose preceding the envelope, or from a marker naming the phase when there is no such prose. It fires only when `phase_id` matches and every other required field is present. `select` runs a scan without the fill first and only falls back to a scan with it when the text holds no complete envelope at all.

Reason: The prose is the producer's own account of the phase, misplaced rather than missing — the same judgement as the misplaced-key decision below, and the same recovery the review path already performs when it assembles an envelope from prose. The two-pass order is what makes it safe: a phase that emits a summary-less draft and then a corrected envelope must settle on the correction, and filling during the first scan would promote the draft to a competing candidate. `FeatureTaskRuntimePhaseOutputSchemaValidatorTest` proved that regression before it shipped.

Alternatives considered: Fill from `reconciliation_evidence.evidence` (rejected: that field is the tree-state evidence, not a phase summary; repurposing it would misreport what the phase did). Marker only, never prose (rejected: it discards a sentence the producer actually wrote and that is already on the wire). Relax `matches` to drop `summary` from the required set (rejected: the field stays contractually required, and the envelope written back carries a real value rather than a hole later readers must handle).

## [2026-08-24] A bare evidence string is promoted to reconciliation_evidence, not rejected

Context: WE-4860 subtask 2's implement receipt emitted `reconciliation_evidence` as the evidence string itself rather than `{ reconciled, evidence }`. The producer-projection gate rejected it and, at a one-attempt budget, blocked the run — discarding a 22KB receipt whose read-only sweep was already on the wire. `RealValidatorReceiptFixLoopConvergenceTest` pinned that rejection deliberately, as one of three SKILL-152 classes "canonicalization must never paper over".

Decision: `FeatureTaskRuntimeProjectionCanonicalizer` promotes a non-blank string at `reconciliation_evidence` to `{ reconciled: true, evidence: <trimmed> }`, recorded as a new `scalar_promoted_to_object` transform. A blank string is left alone. The SKILL-152 guard's line is restated as *whether the repair loses producer content or invents an assertion*: the missing-`evidence` and over-length-`evidence` classes still reject, and the type-mismatch class moves across.

Reason: `reconciled` is `const: true` on the receipt variant, so the promotion asserts nothing the contract had not already fixed, and the string it promotes is exactly the `evidence` the producer wrote — nothing is lost or invented. The original guard grouped this with two classes that genuinely do lose or fabricate content; the distinguishing test it names separates them once `reconciled` being a const is taken into account. Not blocking, and interpreting output that is not shaped exactly as expected, is the standing preference.

Alternatives considered: Keep the rejection and fix only the prose (rejected: the prose already showed the correct object shape, so it was not a briefing gap, and the gate would still discard a receipt already emitted). Promote a blank string too (rejected: `evidence` is `nonBlank`, so it trades a type error for a value error while manufacturing a `reconciled: true` claim the producer never made). Promote in the structural repair walker beside the root-key demotion (rejected: that layer is phase-shape-only and phase-agnostic; this is projection knowledge, and canonicalization already owns the field and runs immediately before validation).

## [2026-08-24] A finding verification refuted is not carried, so the repair receipt owes it no entry

Context: On wftr-20260824-125937-qn99 review reported three findings and `verify_findings` refuted `F-003`, a nit. The fix phase closed the two survivors and reported exactly that. The repair-receipt coverage gate then rejected the round, because it measures against `reviewState.passResults.last().findings` — the raw review output, which nothing subtracts the refuted findings from. The round blocked with both real findings already fixed on the tree. The phase prose carried the same contradiction: it opened with "Address every *verified* finding from verify_findings" and then declared every carried finding in scope, so the agent's reading was the defensible one.

Decision: `featureTaskRuntimeCarriedFindings` becomes the single definition of the carried set for both the coverage rejection and the omitted-findings retry reason, and drops every finding whose durable ledger row records `verification_disposition = rejected` for that pass. The refuted refs are read from the unaddressed-findings ledger scoped to the pass being repaired, never workflow-wide: each pass renumbers from `F-001`, so an unscoped read would let an earlier pass's refutation waive whichever finding inherited its ordinal. A ledger that cannot be read waives nothing. The parser's coverage path now also stabilizes refs, which it previously skipped — a review that omitted a ref failed coverage on an identity it never had.

Reason: Verification exists to drop findings that do not survive scrutiny. Requiring a `no_edit_required` entry for a claim the runtime itself refuted made the stage decorative and blocked a round on paperwork the runtime had already decided was unnecessary. Not blocking, and interpreting what the runtime already knows, is the standing preference — the same judgement as the misplaced-key decision below.

Alternatives considered: Synthesize the `no_edit_required` entry from the refutation reason (rejected: it writes a repair-ledger row for work no one did, and the ledger's value is that every row is a real decision). Fix only the prose (kept as well, but it cannot settle a receipt already emitted, and the gate would still block a correct one). Read refuted refs from `review_run_finding_verdicts` instead of the ledger (rejected: it needs the review run id re-derived from phase output at a seam that holds only the review state, and the ledger is the merged record the reducer already writes).

## [2026-08-24] A key placed beside produced_outputs is moved into it, not rejected

Context: An implement receipt on wftr-20260824-125937-qn99 carried `reconciled_state` at the envelope root instead of inside `produced_outputs`. The closed root rejected it as an unknown property, discarding 42KB of output describing 227 changed files that were already on disk. The contract calls the reconciliation report an *additional* report, which reads as a sibling of `produced_outputs` rather than a member of it.

Decision: `PhaseOutputExpectedShape.align` gains the mirror of its nested-required-field pass: a root key outside the envelope's declared root fields moves into `produced_outputs`. A member `produced_outputs` already states keeps its value; the stray root copy is dropped either way. `ENVELOPE_ROOT_FIELDS` is pinned to the schema's root properties by a parity test.

Reason: The envelope root is closed and `produced_outputs` is open, so an undeclared root key can only be a misplaced member — the shape already says where it belongs. Correcting placement in the capture we hold beats spending a session regenerating work the producer already did, which is the same judgement as the 2026-08-20 decisions to repair in place rather than relaunch.

Alternatives considered: Give envelope failures their own retry budget (rejected: this is the salvage relaunch the 2026-08-20 decision removed after observing zero recoveries, and a second process cannot see the first session's context). Fix only the prompt wording (kept as well, but it cannot recover a receipt already emitted). Demote by an explicit key allowlist (rejected: the closed root already identifies a stray key, and an allowlist would miss the next misplacement).

## [2026-08-24] Remove provider-reported review accounting
Context: Provider token fields use incompatible conventions and no review decision consumes the resulting aggregates.
Decision: Remove provider-token review models, thresholds, folding, projection, and enforcement while retaining runtime-owned byte and count accounting.
Reason: Repairing convention-specific accounting would add an unvalidated measurement without a caller; local byte-derived estimates remain meaningful and load-bearing.
Alternatives considered: Normalize provider values during decoding (rejected: it would preserve an unusable cross-provider metric and expand the transport contract).

## [2026-08-21] Soft-admit findings for verification; prose still settles

Context: Prose-only review emptied merge findings, so claim verification always no-oped even when the parent named concrete defects.

Decision: Soft-parse optional `[F-XXX]` lines from parent stdout into merge findings for claim verification and adjudication. Never fail the lane on register shape. Keep advance settlement on parent `verdict:`; attach soft findings to the feature-task envelope only after that reduction.

Reason: Verification needs structured F-ids; settlement must stay relaxed and not let Minor-only rows flip `changes_requested` to approved.

Alternatives considered: Restore hard register gates (rejected). Change `outcomeFor` ordering globally (deferred; local assemble order preserves prose-first without wider verdict churn).

## [2026-08-21] Review is single-agent prose; dual-agent lanes disconnected

Context: Parallel lane register parsing blocked runs on format drift while findings were meant for the same review owner to interpret.

Decision: Disconnect dual-agent `agent2` paths. Inline and delegated review use one parent agent; delegated specialists return raw text to that parent with no register verification. Remediation opens only from an explicit parent `verdict`.

Reason: Machine admission of register lines dropped usable findings and blocked on punctuation; prose plus verdict is enough for advance vs `implement_fix`.

Alternatives considered: Soften the parser only (rejected: still two agents and a hard merge gate). Keep dual lanes under one owner (deferred: disconnect first).

## [2026-08-20] Output-gate failures block on the first invalid envelope

Context: The one salvage agent launch after a schema-invalid audit did not recover. SKILL-202 burned both attempts on missing `verdict` then prose in `carried_gap_dispositions.evidence.observation`.

Decision: Cap the per-visit output-gate correction budget at one. Programmatic extract-and-shape-repair still runs on the existing capture. If that capture is still invalid, the run blocks. No second agent launch.

Reason: The salvage prompt did not convert contract misses into valid envelopes in practice; it doubled latency and still blocked.

Alternatives considered: Keep the salvage launch (rejected: observed zero recoveries on the SKILL-202 audit path).

## [2026-08-20] Extracted phase JSON is aligned to the expected shape; gate retries cap at two

Context: Audit kept relaunching because an extra `}` closed the envelope before `verdict`, or `verdict` sat under `produced_outputs`. Schema-invalid retries had no cap.

Decision: Walk the capture for JSON, keep the object that matches the phase's expected fields, and repair that object in place (drop extra closers, pull trailing or nested required fields onto the envelope). If programmatic salvage cannot accept it, one last agent launch receives the original capture plus the expected shape; that result is extracted and validated the same way, and a second failure blocks.

Reason: The agent already emitted the envelope. Regenerating it burns the session; one salvage pass is enough to catch a remaining contract miss, then the run must stop.

Alternatives considered: Keep syntax-only delimiter repair and uncapped schema retries (rejected: SKILL-201 spent 35 audit launches on the same extra `}`).

## [2026-08-20] Phase JSON repair keeps the existing envelope; the agent does not regenerate it

Context: A complete valid audit envelope was rejected because surrounding prose had a bare `}`, which exhausted the format-retry budget and relaunched the phase.

Decision: Structural repair is library-owned. Parse with Jackson, compare to the expected envelope shape, and repair the existing capture (drop extra closers, add a missing closer when bounded). Do not ask the agent to generate a new envelope. Reject only when there is no unique complete candidate.

Reason: Format retries repeat the whole phase. An extra bracket around an already-valid object is a syntax fix, not a new authoring turn.

Alternatives considered: Keep rejecting outside closers so agents learn to omit them (rejected: it burned the format cap on wrapping, not on the envelope).

## [2026-08-20] Validate uses only the pack-declared collect-all command

Context: The validate agent ran AGENTS.md extras (`npx agnix --strict`, `skill-bill validate`) after Gradle was already green, then blocked on those results.

Decision: Validate may run only the pack `validation_gate.collect_all_full_gate_command` (and targeted tasks that belong to that gate while repairing). The prompt names that argv and forbids repo-root checklists. `npx agnix --strict` is no longer in AGENTS.md.

Reason: Agnix lints instruction files. It is not the Kotlin pack gate. Mixing the two made a green `./gradlew check` look blocked.

Alternatives considered: Keep agnix on the maintainer list and hope the phase prompt wins (rejected: AGENTS.md is always applied).

## [2026-08-20] Validate session owns collect-all and confirmation

Context: Runtime-owned collect-all parsed findings, deleted the log, and told the agent not to run the gate. "Do not rerun the full gate after every finding" became "this process cannot run check."

Decision: The validate agent runs the pack collect-all gate, reads that output, fixes the set, then runs one confirmation check. The runtime may still verify once after the session. Parsed findings are a hint.

Reason: The working loop is check, read the real output, fix, confirm. A finding list without the log is not that loop.

Alternatives considered: Keep runtime-owned collect-all and only allow targeted module tasks (rejected: the agent still never sees check output).

## [2026-08-20] Checkpoint-ref prune lifecycle supersedes amend-era ref-retention trigger (SKILL-190 subtask 6)

Context: Subtask 4's ref-based remediation reconciliation kept checkpoint refs for the life of a
subtask; subtask 6 adds a gated prune after push plus recorded `commit_sha`, reset-driven pruning,
and idempotent resume.

Decision: Prune `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/*` only after the subtask
commit is pushed and the decomposition manifest entry carries a non-blank `commit_sha`; hard reset
prunes without that gate; blocked or abandoned subtasks retain refs.

Reason: Refs remain the recovery surface until the deliverable commit is durable on the branch and
in the manifest; afterward they would only grow the namespace without adding reachability.

Revisit when: prune eligibility or the checkpoint namespace layout changes.

## [2026-08-19] Ref-based remediation reconciliation supersedes compensating soft-reset (SKILL-190 subtask 4)

Context: Subtask 3 introduced runtime-owned amend semantics; the SKILL-176 compensating soft-reset and
HEAD-rewrite reconciliation path contradicted amend-owned history and reintroduced SKILL-189 empty-review
diffs when checkpoint commits were orphaned.

Decision: `reconcileRemediationBaseCoherence` resolves the latest `review_fix` base through checkpoint
refs, not branch ancestry; unresolvable bases return a typed blocked outcome with `skill-bill goal repair`
guidance instead of rewriting to HEAD. `rollbackRemediationCheckpointCommit` restores the prior checkpoint
ref (or removes the first subtask commit) and is idempotent when HEAD already moved.

Reason: Refs preserve pre-amend commits the branch no longer names; soft-reset to `parentSha` fails once
amend dissolves the intermediate commit object the old rollback targeted.

Alternatives considered: Keeping HEAD rewrite for recorded-but-superseded — rejected; that was the
SKILL-189 failure door. Retaining parent-only soft-reset — rejected; amend orphans the parent link the
rollback relied on.

Superseded by: checkpoint-ref prune lifecycle (SKILL-190 subtask 6, 2026-08-20).

## [2026-08-17] Checkpoint-identity 0.2 keeps its parity test; quarantine enum widens without a bump (SKILL-190 subtask 2)

Context: Bumping the checkpoint-identity contract to 0.2 hit two governance collisions the parent
spec flagged. First, `CLAUDE.md` forbids new tests while the runtime-contract recipe requires a
parity test for every version bump. Second, recording the quarantine of a legacy checkpoint-identity
store needs a `rejection_class` the quarantine schema's enum does not carry.

Decision (AC-008 parity): The collision is abstract here — `FeatureTaskRuntimeCheckpointIdentitySchemaContractVersionTest`
already exists and is version-agnostic, asserting the YAML const against the Kotlin constant. Follow
the contract rule by retaining that test unchanged and adding no new test file. The subtask therefore
proceeds on a recorded decision, not an unrecorded default.
Alternative rejected: waiving the parity check on the no-new-tests rule — it would leave a
YAML-versus-Kotlin divergence with no gate, which is exactly the drift the recipe exists to catch.

Decision (quarantine enum): Add `checkpoint_identity_contract_version` to the quarantine schema's
`rejection_class` enum WITHOUT bumping the quarantine contract from 0.3. Enum widening is
read-compatible in the only direction that occurs: a store is written and read by the same installed
runtime, and every 0.3 record stays valid under the widened enum.
Alternative rejected: bumping the quarantine contract to 0.4 per the usual per-change precedent. The
quarantine store has no quarantine-of-quarantine recovery path, so a bump would loud-fail every
in-flight workflow that already holds evidence — trading the wedge this subtask removes for a worse
one.
Revisit when: the quarantine record gains or changes a field (not just an enum member), or a
downgrade path where an older runtime reads a newer store becomes real.

Known residual gap handed to subtask 4: `FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence`
decodes the checkpoint-identity store at run startup and rethrows anything that is not an
`InvalidGoalSubtaskReviewStateSchemaError`. That seam is correctly loud (AC-006) but runs BEFORE
`appendCheckpointIdentity`'s quarantine repair, so a goal-continuation run holding a 0.1 store can
still fail there first. Subtask 4 owns that consumer; this subtask audited it and left it unedited.

## [2026-08-15] FULL validate proves repairs by confirmation identity closure
Context: Last-subtask FULL collect-all discovery still treated a completed repair agent payload as proof, so omitted identities and leftover confirmation findings could look green.
Decision: Persist a covering repair plan and require a substantiation receipt per discovery identity before confirmation; green confirmation is identity closure on the confirmation finding set, not measured PASSED alone.
Reason: Suite proof stays one collect-all confirmation run; per-finding Gradle or filtered `--tests` launches recreate the SKILL-176 39-run failure. BUILD_ONLY stays compile/build-only without receipts or identity closure.
Alternatives considered: Agent-run full_gate_command or per-test substantiation — rejected. Bumping persistence contract versions for additive plan/receipt keys — rejected; absent keys decode empty.
Revisit when: confirmation closure needs a different identity key than exact module|ruleOrTestId|message|location.

## 2026-08-10 — Review remediation gate is Blocker or Major (SKILL-178)

Context: Governed skill content and content-lock tests still stated the old
Blocker-only reopen rule after subtasks 1–3 widened runtime severity gates so
Blocker and Major both reopen `implement_fix` and hard-block advance.

Decision: Governed content, playbook/code-review remediation-delta prose, and
parity locks describe the Blocker-or-Major rule; Minor and Nit stay ledger-only
with retrieval only via `skill-bill goal findings --issue-key <KEY>`. Durable
wire key `blocker_dispositions` stays named as-is.

Reason: Content must agree with the runtime predicates already shipped; renaming
durable disposition keys is a separate migration.

Alternatives considered: Leaving Blocker-only prose until a later docs pass —
rejected; locks would keep encoding the wrong rule. Renaming `blocker_dispositions`
in this sweep — rejected; out of scope and breaks durable decode.

Revisit when: disposition obligations widen from prior-Blocker ids to every
addressed finding in the review-execution directive itself.

## 2026-08-10 — Validate-phase build/test/gate execution is runtime-owned (SKILL-180)

Context: Validate previously told the agent to invoke `bill-code-check`, so
gate-run count, batching, and terminal cache-bypass evidence were claims rather
than measurements. Intermediate cache-served greens could also satisfy a
terminal outcome without executing work.

Decision: When the dominant platform pack declares `validation_gate`, the
runtime owns gate execution (pack-declared argv, including the cache-bypassing
terminal variant), measures each run, projects bounded findings to the validate
agent, and persists `gate_run_count` / `gate_runs`. The agent repairs findings
and must not invoke the gate or any quality-check skill. Absence of a
declaration falls back to agent-run validate with a surfaced degradation.
Audit and repair evidence remain read-only repository facts.

Alternatives considered: Agent-reported gate_run_count (rejected). Hardcoded
Gradle cache flags in the runtime (rejected; packs declare bypass argv).

## 2026-08-10 — producer_output_evidence identity includes agent_id (SKILL-176)

Context: Re-entering a phase attempt under a different agent (SKILL-15
`review:0:2`) crashed retention: the four-part key already held another
producer's immutable bytes, so read-back Conflict aborted phase recording.

Decision: Widen identity to `(workflow_id, phase_id, generation, attempt,
agent_id)`. Same-agent divergent bytes still Conflict; cross-agent rows
coexist. Do not supersede or overwrite — AC-002 forbids in-place mutation,
and AC-003 requires both producers remain reconstructable.

Reason: `agent_id` is already durable on every row, so including it needs no
backfill; widening the key is the minimum change that keeps immutability and
lets a second producer land without terminating the run.

Alternatives considered: Last-writer-wins or supersede — rejected; violates
AC-002 and loses reconstructability. Advance attempt on agent switch —
rejected; hides the identity bug and is out of scope.

Revisit when: evidence must be shared across producers for one attempt without
agent scoping.

## 2026-08-10 — remediation checkpoint sha and branch tip stay paired (SKILL-176)

Context: On SKILL-15, remediation checkpoint `73993c8` was recorded as
`remediation_base_sha`, then the branch tip moved to sibling `9d814e8` (same
parent `173fb03`) without a second checkpoint-identity record. The durable base
became unreachable. Candidate runtime producers of that sibling topology were
eliminated: index restore on a failed checkpoint never moves HEAD; a crash
between commit and `updateReviewState` leaves committed-but-unrecorded rather
than a recorded orphan sibling; resume Skip+re-record converges on HEAD; no
runtime reset/rollback API existed. The stranding requires a post-record history
rewrite that moves the tip off the recorded sha without a coupled base write.

Decision: (1) A remediation Stage commit and its `remediation_base_sha` write are
one unit — the commit sha is passed into `updateReviewState`, and a failed base
record soft-resets HEAD to the pre-commit parent so the ref and the durable row
both remain at the pre-commit state. (2) On goal-child resume, reconcile
committed-but-unrecorded and recorded-but-superseded bases to the branch tip (or
latest review_fix checkpoint still on the branch) before review preparation
consumes the base, emitting durable `goal_review_base_recoveries` evidence.
Subtask 2 recovery remains the degradation path for pre-existing orphans.

**Superseded 2026-08-19 (SKILL-190 subtask 4):** resume reconciliation and compensating rollback now use
checkpoint refs under amend semantics; the HEAD rewrite and parent-only soft-reset described above no
longer apply. See the 2026-08-19 entry in this file.

Reason: Git and SQLite cannot share one ACID transaction; compensating soft-reset
plus resume heal close both the crash window and the post-record rewrite window
without a second reconciliation pass on the healthy path.

Alternatives considered: Always reset on identity-write failure for every
checkpoint intent — rejected, only remediation bases are scope-critical at this
seam; migrate/backfill historical rows — rejected, heal at read/resume only.

Revisit when: a runtime-owned history rewrite (amend/rebase) is introduced, at
which point that path must call the same paired base update.

## 2026-08-09 — runtime is the only feature engine; prose and OpenCode/zcode are removed from the product (SKILL-175)

Context: Runtime became the default feature engine and now owns the guarantees
prose cannot deliver: DB-owned phase loop, shared preplan hydration, projection
budgets, worker leases, agent-independent resume. Keeping prose alongside it
means a second product with weaker semantics — dual skills, MCP tools, a CLI
workflow family, telemetry events, IDE status enums, and runtime↔prose parity
locks. OpenCode and zcode are install-first-class but feature-runtime refused,
and their refusal message points operators at prose. This entry **supersedes**
the 2026-06-27 entry "opencode is prose-only: runtime mode refuses whenever the
resolved agent is opencode" in full. That entry's body is left intact as
history; its stance no longer governs.

Decision:

1. **Runtime is the sole feature execution engine.** There is no mode selector
   on feature entry — no `mode:prose`, no `mode:runtime`, no engine choice
   exposed by skills, CLI, or MCP.
2. **The prose engine surface is deleted, not renamed.** The legacy prose
   workflow and subtask-runner surfaces, `feature_task_prose_*` / legacy
   `feature_implement_*` / `goal_prose_*` MCP tools, the `skill-bill workflow`
   family (`TASK_PROSE`), `implement-stats`, `FeatureImplement*`,
   `WorkflowFamily.IMPLEMENT`, and the IDE `feature-task-prose` family go away.
   Nothing is renamed forward into a runtime-flavoured equivalent.
3. **OpenCode and zcode are removed from the product entirely.** They are not
   kept as install targets, not kept as detection signals, and **must not be
   preserved as a permanent refuse tier or an "unsupported agents" list** —
   that prohibition is explicit, not implied. `RUNTIME_REFUSED_AGENTS`,
   `RUNTIME_REFUSED_AGENT_MESSAGE`, and `isRuntimeRefusedAgent` are **deleted**,
   not rewritten to carry a different rejection reason. `InstallAgent.OPENCODE`
   and `InstallAgent.ZCODE` and their provider/link/MCP/native-agent cases are
   deleted with them.
4. **A future OpenCode return is a clean new integration, never an un-delete.**
   It starts from a working headless driver and a real runtime child model,
   with no compatibility shim carried forward from this cut and no prose
   fallback.
5. **The cutover is dependency-ordered, not a single sweep.** Prose shares
   `feature_task_workflows` with runtime through the `mode` column, so that
   table is never dropped blindly; the order is stance → callers + OpenCode/zcode
   purge → prose skill deletion → MCP/telemetry → CLI → persistence/IDE →
   tests/docs, and the row policy below governs every persistence step.
6. **No dual maintenance survives this feature.** Runtime↔prose parity tests and
   any "must work on both paths" requirement are retired; runtime is the sole
   authority and no future change re-establishes a second engine to keep in
   lockstep.

### In-flight prose row policy (binding on subtask 6)

The policy is **quarantine + loud-fail resume**. It is never silent
reinterpretation of a prose row as a runtime row, and it is never a one-shot
migration that rewrites historical rows. Subtask 6 implements exactly this, and
must not re-decide it. The three row populations each get a distinct rule:

1. **`feature_task_workflows` rows with `mode = 'prose'`.** Rows remain readable
   for history. Every resume/continue/update path that encounters one **must**
   raise a typed error naming the runtime re-run path (`skill-bill goal <KEY>`)
   rather than degrading or reinterpreting. Two distinct `mode` CHECK
   constraints spell `'prose'`, and **both must retain it**, for different
   reasons:
   - `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseSchema.kt:359`
     is the `feature_task_workflows` CHECK — the one this rule exists to
     protect. Retaining `'prose'` here is what avoids a SQLite table rebuild
     and keeps quarantined rows insert-compatible with their own history.
   - `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseMigrations.kt:50`
     is the `feature_task_execution_identities` CHECK — a different table
     (`DatabaseMigrations.kt` never creates `feature_task_workflows`; it
     references it only as an FK target at lines 55 and 89). Retaining
     `'prose'` here is what the identity-schema retention paragraph below
     depends on, so `decodeIdentityMode` can still decode a quarantined row.

   Both are legacy read-only values; every **write** path must refuse `'prose'`
   above the schema.
2. **`feature_implement_sessions` rows and their stats builders.** The table and
   its rows are retained as read-only history. There **must** be no live writer.
   The stats surfaces built on it (`FeatureImplement*Stats`,
   `implement-stats`) are removed, and `StaleSessionReconciler` /
   `StaleReconciliationCandidateQuery` **must** stop treating those sessions as
   reconciliation candidates.
3. **`goal_run_sessions` prose attribution.** Existing rows keep their recorded
   `mode` value verbatim. No new prose attribution is ever written, and goal
   continuation **must not** treat a prose-attributed session as resumable.

**Override of parent inventory rows.** This policy **supersedes** five rows of
the parent inventory in
`.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md`: section
D's `FeatureTaskWorkflowMode.PROSE` / `mode` CHECK including `prose` ("Remove
after migration") and section D's shared `feature_task_workflows` prose branch as
it applies to reads, and section E's `feature-task-execution-identity-schema.yaml`
`prose` enum ("Remove"). Where they conflict, this entry governs. Concretely, the
mode **decode** path — `FeatureTaskWorkflowMode.PROSE`, its `wireValue` /
`fromWireValue` lookup, and `decodeIdentityMode` in
`runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/WorkflowStateStore.kt:560`
— and the identity-schema `prose` enum value **must both be retained as legacy
read-only values**. A quarantined row must decode successfully so the refusal is
raised as the typed runtime re-run error from rule 1 above, not as
`InvalidFeatureTaskExecutionIdentitySchemaError("mode 'prose' is not supported")`
from the schema decoder. Deleting the enum value or the schema enum would convert
the mandated loud, actionable refusal into an opaque schema-decode failure and
would make history rows unreadable. Only the **write** and **resume** paths drop
prose.

The same rationale extends to a **third decode path over the same retained rows**,
which supersedes two further parent rows: `spec.md:107`
(`FeatureImplementWorkflowDefinition` + `FeatureImplement*` stack → "Remove") and
`spec.md:112` (`WorkItemKind.FEATURE_TASK_PROSE` → "Remove"), **as they apply to
the work-list read path only**.
`runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/worklist/SQLiteWorkListRepository.kt`
maps `mode = 'prose'` to the wire kind `feature-task-prose` (line ~42) and builds
`validWorkStates` (line ~120) from
`FeatureImplementWorkflowDefinition.definition.workflowStatuses`. Deleting either
symbol outright makes `list()` throw on **any** database holding a single legacy
prose row — failing the entire work-list read rather than degrading that one row,
which is the opposite of the mandated per-row refusal and breaks rule 1's
readability guarantee. Therefore:

- `WorkItemKind.FEATURE_TASK_PROSE` and its `feature-task-prose` wire value are
  **retained as legacy read-only values**, exactly like
  `FeatureTaskWorkflowMode.PROSE`. A quarantined row lists; acting on it raises
  the typed runtime re-run error.
- Once the `FeatureImplement*` stack is deleted, the retained
  `FEATURE_TASK_PROSE` kind resolves its valid states from a **frozen literal set
  of the historical prose workflow statuses**, declared alongside the retained
  kind rather than by importing a workflow definition. It is a closed constant
  used only to keep history rows decodable — not a surviving workflow definition,
  and nothing dispatches on it.

Everything else on `spec.md:107` and `:112` — the prose runner, its services,
skills, MCP tools, and any dispatch on the kind — is deleted as the parent rows
say.

Already-installed databases reach the quarantined state **through the read and
resume code paths, not through an appended migration body**: appending a
statement to a migration that has already been applied is a silent no-op on
existing DBs, so a schema-side quarantine would never reach any real user's
database. The refusal therefore lives in the store/service read path, which every
existing DB executes on the next run.

Two separate `mode = 'prose'` surfaces sit near goal continuation and **must not
be confused for each other**:

1. **The issue-key backfill branch**, `mode = 'prose' AND ...goal_continuation.enabled`
   inside `recoverGoalContinuationWorkflowIssueKeys` at
   `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseColumnMigrations.kt:188`.
   This is an `UPDATE` that backfills `issue_key` from `artifacts_json`; it is
   **not** a continuation-candidacy predicate and removing it does not stop any
   row from being resumed. It **must be retained**, because rule 1 guarantees
   quarantined prose rows stay readable for history, and a legacy prose row
   whose `issue_key` was never backfilled would otherwise surface in the work
   list with a null `issueKey`. Backfilling an identifier is a read-side repair,
   not a prose write path, so it does not violate the "no new prose writes" rule.
2. **The real continuation candidacy path**, `findGoalChildFeatureTaskCandidates`
   at `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/WorkflowStateStore.kt:342`,
   delegating to the private `findFeatureTaskCandidates` query at `:362-403`,
   which selects on `identities.route_scope` (`'goal_child'`) plus repository
   identity and issue key. It does inspect `workflows.mode`, but only in the
   `standalone` branch (`:378`), so a legacy `mode = 'prose'` goal-child row is
   returned as a resume candidate today. The refusal from rule 1 does **not**
   belong in this query, and emphatically not in the deletion/count statements
   nearby (`deleteGoalChildWorkflowsByParent` `:294`,
   `deleteGoalChildWorkflow` `:311`, `countGoalChildIdentities` `:351`) — those
   run during ordinary goal cleanup and history counting, which rule 1 requires
   to keep working for quarantined prose rows.

   The refusal belongs **one layer up**, in
   `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskContinuationLookupService.kt`,
   on the candidate list returned by `lookup` (`:60-77`): a candidate whose
   workflow decodes to `FeatureTaskWorkflowMode.PROSE` raises the typed runtime
   re-run error instead of being handed back as resumable. That placement covers
   both route scopes through one seam, keeps the store a pure read, and leaves
   delete/count paths untouched. Subtask 6 adds the refusal there, not in the
   store and not in the column migration.

### Keep/delete heuristic and grep allowlist

English "prose" wording is **out of deletion scope**. Only the product mode named
`prose` is deleted. Concrete keep examples, all present in the tree today:

- `AGENTS.md` writing guidance: "Write direct, active prose".
- `orchestration/contracts/native-agent-composition-schema.yaml`: "the governed
  prose already…" — "governed prose" means authored skill/pack body text.
- `skills/bill-pr-review-fix/content.md`: "write 1-3 sentences in plain prose" —
  review/PR reply language.
- `orchestration/contracts/review-context-schema.yaml`: "a bounded prose summary".
- `orchestration/contracts/platform-pack-schema.yaml`: "Optional prose
  tie-breakers" and "Short prose describing the area's specialist focus."

The allowlist for `opencode` / `zcode` product-token greps is exactly
`.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/**` and
`.feature-specs/done/**`. There is no live product keep-list: any other hit is a
removal surface.

### Ordering precondition on subtask 3

Runtime phase prompt directives currently lockstep with the prose native agents,
so one native-agent source holds the only copy of some governed briefing text.
Before the prose source is deleted, the governed briefing text **must** first be
re-homed into the runtime phase-briefing composition in
`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhaseBriefingAssembler.kt`
(with `FeatureTaskRuntimeBriefingRendering.kt`). This is an ordering precondition
on subtask 3, not a suggestion: deletion before re-homing loses the only
surviving copy.

Reason: two engines sharing `feature_task_workflows.mode` doubles the
maintenance surface while only one of them can honour the durability guarantees
the product sells. A permanent refuse tier for OpenCode/zcode would keep agent
matrix noise and advertise a support level that does not exist. Quarantine over
migration keeps history truthful and makes the failure loud at the exact moment
an operator would otherwise get a half-executed run.

Alternatives considered: (a) keep OpenCode/zcode as install-only targets with a
refuse-and-redirect message — rejected, it is a fake support tier and the
redirect target no longer exists; (b) one-shot migration of prose rows to
runtime — rejected, the two engines' durable state is not equivalent and a
rewritten row cannot actually resume; (c) rename the prose stack to a runtime
variant — rejected, it preserves dead code paths under new names.

Non-goals: implementing an OpenCode or zcode feature runtime; deleting English
"prose" wording; rewriting historical `.feature-specs/done/**` archives or git
history; changing review `mode:inline|delegated|auto`; guaranteeing cleanup of
symlinks already written to user machines under `~/.config/opencode` or
`~/.zcode` (release-note guidance only).

Revisit when: someone brings a working headless OpenCode (or zcode) driver that
can sustain the runtime child model, at which point it enters as a new
integration; or when telemetry shows prose-quarantine loud-fails still firing
long after the cut, which would mean the operator message is not landing.

## 2026-08-08 — link and compile toolchain pin is JDK 21 (SKILL-166)

Context: The Kotlin runtime compiled and linked against JDK 17 while the
intellij-plugin and host JDKs had already moved to 21.

Decision: Raise `JDK_VERSION`, `LINK_JDK_VERSION`, build-logic source/target,
and the four CI Temurin pins to 21 in one change; keep Badass Runtime and the
explicit additive `IMAGE_MODULES` strategy unchanged.

Reason: One pinned toolchain across compile, jlink, CI, and release docs
avoids mixed-JDK drift; membership of `IMAGE_MODULES` needed no add/remove on
the JDK 21 module graph.

## 2026-08-07 — reject audit/review single-pass merge (SKILL-164)

Context: Checkpoint-keyed shared review evidence made it tempting to collapse
`audit` and `review` into one agent pass over the same derived artifact.

Decision: Keep audit and review as separate phases. The single-pass merge is
rejected and not to be re-litigated.

Reason: The phases read divergent evidence sets (audit still needs unchanged
files that produce no diff), they settle into divergent backward edges
(`audit_gap` → `implement` versus `review_fix` → `implement_fix` under
`MUST_MATCH`), audit-first ordering is itself the cost optimization that keeps
specialist fan-out off trees about to be rewritten, and one agent holding both
evidence bars degrades the audit.

Alternatives considered: Merge audit and review into a single pass over the
shared evidence — rejected for the four reasons above.

Revisit when: none; settled for this feature.

## 2026-08-11 — Non-terminal-only plan cascade with provenance restamp (SKILL-181)

Context: SKILL-160 cascaded every sibling plan under `--include-shared-preplan`
because `recoveryProgress` re-validates all ordered plans against the governing
shared provenance with no status filter. Leaving a complete sibling mismatched
wedged resume. WE-4719 showed that wiping a complete+commit plan row destroys
useful planning provenance for no benefit.

Decision: Reverse the SKILL-160 cascade breadth. Cascade only plan rows whose
manifest subtask is **not** (`status == complete` AND non-blank `commit_sha`),
on both `--include-shared-preplan` and in-run heading-set refresh. When survivors
remain, soft-invalidate the shared preplan (keep the parent row so FK ON DELETE
CASCADE cannot wipe them) instead of deleting it. In the same transaction that
writes a replacement shared preplan (refresh replace, or relaunch regeneration
after invalidate), restamp retained plan rows' provenance to the new shared
provenance without changing plan payloads or runtime manifest fields.

Evidence that decided it:
- Complete-with-commit plans are still read via `findStoredSubtaskPlan` for hash
  recovery, but they are never re-hydrated into a fresh child; discarding them
  only loses history.
- Soft-invalidate avoids mid-transaction `PRAGMA foreign_keys` toggles (illegal
  inside an open SQLite transaction) while preserving survivors across discard.
- Restamp-at-write keeps `recoveryProgress` provenance equality strict for every
  remaining prepared plan; non-terminal leftovers with mismatched provenance
  still loud-fail.

Alternatives considered: (1) Permanently ignore terminal plan provenance in
recovery — rejected; weakens hydration checks for non-terminals if the filter
drifts. (2) Orphan plan rows by deleting the shared parent with FK off — rejected;
cannot toggle `foreign_keys` inside the replan transaction. (3) Keep
cascade-everything — rejected by WE-4719 cost.

Revisit when: none; settles the SKILL-160 revisit clause.

## 2026-08-05 — `--include-shared-preplan` cascades every sibling plan row (SKILL-160)

Context: Discarding the goal-wide shared preplan while leaving sibling
`goal_subtask_plans` rows would provenance-mismatch those survivors against the
regenerated preplan. The subtask asked for either cascade of non-terminal
plans or an explicit reject naming blockers.

Decision: Cascade **every** stored sibling plan row for the goal (terminal and
non-terminal), while leaving runtime fields (`status`, `commit_sha`,
`workflow_id`, out-of-band acceptances) untouched. Non-terminal-only cascade
was rejected.

Evidence that decided it:
- `GoalPlanningPreparationCheckpoint.recoveryProgress` re-validates all ordered
  plans against `expectedProvenance` with no status filter; a leftover complete
  plan whose provenance no longer matches throws
  `IncompatibleGoalPlanningPreparationRecoveryError` and wedges resume.
- `GoalPlanningSweep.descriptor` still reads complete plans via
  `findStoredSubtaskPlan` (hash recovery for completed sub-specs), so terminal
  plans are not inert bytes after completion.
- `GoalPlanningPreparationStore.replaceSharedPreplan` already
  `DELETE FROM goal_subtask_plans` for the same reason — leaving survivors
  strands rows whose provenance can never match.
- A non-terminal-only cascade would leave complete siblings mismatched and
  wedge; a reject-when-complete-siblings-exist path would break the ST2 e2e
  (subtask 3 with 1–2 complete).

Alternatives considered: (1) Reject when any surviving plan would mismatch —
rejected because the operator path for goal-wide amendments is exactly the
mid-goal case with complete siblings. (2) Leave mismatch for runtime discovery —
forbidden by the subtask. (3) Non-terminal-only cascade — rejected by the
evidence above.

Revisit when: recovery or hydration gains a status filter that permanently
ignores terminal plan provenance, with tests proving complete plans are never
read after completion.

**Superseded by 2026-08-11 SKILL-181 decision** (non-terminal-only cascade +
restamp). Kept for history.

## 2026-07-04 — internal skills are file-read sidecars; repo paths did not move (SKILL-102)

Context: The feature-execution dispatch targets needed to stop appearing in every
agent's skill list because they are selected by `bill-feature`, not user entry
points. The install pipeline derived listing from the same `content.md`
discovery that drives staging, so hiding a skill required a new internal-skill
classification.

Decision: An internal skill is declared by one optional frontmatter key
(`internal-for: <parent>`). Install renders its governed content as a
`<skill-name>.md` sidecar inside the parent's staged directory (no `skills_dir`
entry, no `SKILL.md` of its own), and the parent invokes it by reading that
sibling sidecar file and executing it in-session — never via the Skill tool.

Reason: The Skill tool on every supported agent resolves only listed skills;
there is no invocable-but-hidden state, so the invocation contract for internal
skills is necessarily a file read. The file-read pattern was already established
for other sibling sidecars (`shell-ceremony.md`, `compose-guidelines.md`) and is
more portable across agents than Skill-tool mechanics. Repo source directories
did not move or rename (PD3) because `WorkflowEngine.CONTINUATION_CONTENT_PATHS`
and `RepoValidationRuntime` content-marker checks bind to the existing repository
paths; moving them would have changed runtime path bindings, workflow identity,
the DB `workflow_name` CHECK constraint, and telemetry constants (PD4) for no
listing benefit.

Alternatives considered: (1) A separate `config.yaml` visibility switch per
skill — rejected as a per-skill preference system, the opposite of a repo-level
authored classification. (2) Moving internal skills' source directories under
the parent — rejected because it breaks runtime path bindings and identity
strings (PD3/PD4). (3) Trimming the
sidecar to a token-light format — rejected by PD6 (behavior parity over token
savings).

Revisit when: A supported agent gains a first-class invocable-but-hidden skill
state, or when internal skills need to be surfaced in a maintainer-only listing
view (the parent spec's deferred open question).

## 2026-06-27 — opencode is prose-only: runtime mode refuses whenever the resolved agent is opencode

Context: A real run (NEWS-141, workflow `wftr-20260626-193556-a4lk`) proved the
runtime-driven phase loop is non-viable under opencode for two independent
reasons: (A) the Kotlin runtime driver runs the whole phase loop synchronously in
one foreground process, but opencode's Bash tool hard-kills foreground commands
at 120 000 ms — a single phase (preplan) took ~241 s, so the driver is guillotined
before even one phase completes; and (B) even with a longer budget, the nested
`opencode run` emits valid contract JSON that the runtime never captures back (the
opencode builder used `usePtyStdio=true` + opencode's formatted/TUI output, so the
phase-output JSON cannot be parsed out of the ANSI stream), leaving the phase
`running` and wedging the loop. opencode is the highest-churn, lowest-usage
runtime target, so fixing either bug is not worth it.

Decision: opencode is prose-only. Runtime mode refuses, loudly and at the
boundary, whenever the resolved runtime agent is opencode by ANY route — host-agent
detection, `SKILL_BILL_AGENT=opencode`, `--agent opencode`,
`--phase-agent plan=opencode`, `--agent-override opencode`, and
`--parallel-review-agent opencode` on the feature-task CLI, plus the invoked agent and `--agent-override`
on the goal CLI — failing fast before opening a workflow, resolving a branch, or
spawning a phase. The single source of truth is one domain set,
`skillbill.install.model.RUNTIME_REFUSED_AGENTS` (`{OPENCODE}`), with the predicate
`isRuntimeRefusedAgent` and `OPENCODE_RUNTIME_REFUSAL_MESSAGE` derived from it; every
layer consumes that set so re-enabling an agent's runtime path is a one-line change
rather than scattered edits that drift. Enforcement is defense-in-depth over two
layers: (L1) the runtime CLI preflights — feature-task, goal, and
`code-review-parallel` — all funnel their reachable agent ids through one shared gate
`skillbill.cli.core.refuseRuntimeRefusedAgents`, which throws a `UsageError` with the
actionable message naming the governed prose alternative and the prose mode;
(L2) the launcher source-disablement —
`OpencodeAgentRunCommandBuilder` is removed and `headlessAgentRunAdapters` filters out
`RUNTIME_REFUSED_AGENTS`, so `FileSystemAgentRunLauncher` yields
`UnsupportedAgentRunLaunch` for opencode (mirroring copilot) as an unbypassable
backstop even if a CLI guard is bypassed, and that deep path carries the same
actionable `OPENCODE_RUNTIME_REFUSAL_MESSAGE` (not a generic reason) so it is as
legible as the preflight. The message is centralized in `runtime-domain` (a plain
const is inert data, allowed by the 2026-05-24 boundary decision) and consumed by
both the CLI and the launcher, so every refusal emits byte-identical wording.

Reason: opencode stays fully usable in prose mode, which runs the identical
governed phase loop in-session with none of the 120s-kill / PTY-harvest problems.
Failing loudly with an actionable message (instead of silently degrading or
wedging) tells the user exactly which path to take. The host-agent DETECTION
(`InvokingAgentContextResolver`) and the `InstallAgent` enum are intentionally
retained: opencode must stay detectable (to refuse) and installable/scaffoldable
(MCP into the user-level opencode config directory, generated opencode agents); only the runtime
LAUNCH path is disabled. No app-layer guard is added (`AgentRunService` /
`FeatureTaskRuntimeRunner` / `GoalRunner` are unguarded) so their
resolution/recording tests stay green and the launcher backstop remains the single
spawner chokepoint. `bill-code-review-parallel` runtime is disabled for opencode the
same way (a parallel-review subprocess hits the same 120s-kill/PTY-harvest wall): its
command now runs the shared preflight on both resolved lanes, so an opencode lane
refuses upfront with the actionable message instead of degrading to a silent one-lane
review.

Non-goals: no change to opencode install/scaffold/MCP, prose orchestration, or
telemetry (all byte-for-byte unchanged); no change to runtime support for claude,
codex, or junie; no removal of opencode from detection or the install enum; no
schema/data migration and no feature flag (refusal-only).

Revisit when: opencode gains a non-TTY harvestable headless mode and a foreground
budget longer than 120s, at which point re-registering an opencode runtime adapter
and dropping the preflights becomes viable.

## 2026-06-26 — SQLite runs in WAL with a busy_timeout for concurrent runs

Context: All runtimes share one global review metrics SQLite file in the user skill-bill state directory,
and nothing prevents concurrent runs (e.g. two goals for two projects at once).
`ensureDatabase` previously set only `PRAGMA foreign_keys = ON`, leaving SQLite on
its rollback-journal default with no busy timeout, so a write that collided with a
concurrent writer failed immediately with `SQLITE_BUSY` ("database is locked") and
aborted the operation — there is no DB-level retry/backoff anywhere in the adapter.

Decision: `ensureDatabase` now sets `PRAGMA busy_timeout = 5000` and
`PRAGMA journal_mode = WAL` (in that order) on every connection, alongside the
existing `foreign_keys` pragma. busy_timeout makes a blocked writer wait-and-retry
inside SQLite instead of erroring; WAL lets readers run concurrently with the single
writer. busy_timeout is set before the journal_mode switch so the WAL transition
itself tolerates a concurrent writer.

Reason: This is the standard low-risk hardening for a shared local SQLite file and
closes the only practical sharp edge of concurrent runs without introducing an
application-level lock or per-project DB files (both rejected: a lock would remove
the ability to run concurrent goals, per-project files would fork the cross-project
review-metrics aggregation the single DB enables). WAL is a persistent property of
the DB file and creates `-wal`/`-shm` sidecars next to the DB — acceptable for a
local user-state database. Re-applying the pragmas per connection is
idempotent.

Revisit when: the DB is moved off a local filesystem (WAL needs shared-memory
support), or measured contention shows 5s is the wrong timeout.

## 2026-06-12 — Retain split `skillbill.contracts.*` package for validator moves

Context: SKILL-52.4 F16 leaves contract DTOs/constants/helpers in
`runtime-contracts` while concrete schema/coherence validators compile from
`runtime-infra-fs` under the existing `skillbill.contracts.*` packages.
Decision: Keep the split package and guard against adding new concrete
`*SchemaValidator` / `*CoherenceValidator` declarations to `runtime-contracts`
main source.
Reason: The package name preserves classpath resource paths and import
compatibility while keeping validator dependencies behind the infra/domain-port
ownership pattern.
Revisit when: Resource paths/import compatibility can be migrated cleanly, or
JPMS/module packaging becomes an active target.

## 2026-06-12 — Keep `runtime-infra-fs` as one adapter module

Context: SKILL-52.4 F17 considered splitting `runtime-infra-fs` into smaller
Gradle modules after validator and filesystem/process ownership moved behind
ports.
Decision: Do not split `runtime-infra-fs` now; keep the filesystem, process,
schema-validation, rendering, git, and staging adapters in the current adapter
module.
Reason: The current module keeps cohesive adapter ownership without adding
premature Gradle/module overhead or new cross-module seams.
Revisit when: Infra-fs package ownership, file count, or build/runtime ownership
pressure makes module-level separation cheaper than the current single adapter
module.

## 2026-05-29 — Ship desktop installers UNSIGNED for v1

**Context.** SKILL-55 subtask 2 produces native desktop installers (`.dmg`,
`.msi`, `.deb`, `.rpm`) via Compose's jpackage integration, each bundling its own
JRE. macOS Gatekeeper and Windows SmartScreen both warn on, or block, software
that is not signed with an Apple Developer ID (notarized) certificate or a
Windows Authenticode code-signing certificate respectively. We do not hold either
certificate for v1.

**Decision.** **SHIP UNSIGNED FOR V1.** We ship the installers unsigned and defer
code signing + Apple notarization to a later release. End users open the app
through the OS "open anyway" escape hatch; the exact steps below are recorded
verbatim so subtask 4 (post-install hint) and subtask 6 (launch FAQ) reuse the
same wording without re-deriving it.

**End-user open-anyway steps (verbatim, reuse these).**

- **macOS (Gatekeeper).** Right-click (or Control-click) the app in Finder ->
  **Open** -> **Open** in the confirmation dialog. Alternatively: **System
  Settings -> Privacy & Security -> Open Anyway**.
- **Windows (SmartScreen).** On the "Windows protected your PC" dialog, click
  **More info** -> **Run anyway**.

**Reason.** Acquiring and provisioning an Apple Developer ID certificate (+
notarization pipeline) and a Windows Authenticode certificate is cost and
process overhead not justified for a v1 launch. Unsigned distribution with
documented open-anyway steps unblocks shipping now; signing/notarization is a
tracked follow-up. The `.deb` / `.rpm` Linux packages have no equivalent
OS-level signing gate for local installs, so this trade-off is macOS/Windows
specific.

**Consumers.** Subtask 4 surfaces a post-install hint pointing at these steps;
subtask 6 embeds them in the launch FAQ. Keep the wording above as the single
source of truth.

## 2026-05-29 — Artifact FILENAME, not embedded version, is the source of truth (macOS diverges)

**Context.** SKILL-55 subtask 2 derives the embedded jpackage `--app-version` from
`project.version` (`0.1.0-SNAPSHOT`). jpackage requires a strict numeric
`MAJOR.MINOR.PATCH`, and macOS jpackage + the Compose Dmg validator additionally
require `MAJOR >= 1`. So `toMacAppVersion` bumps a zero major (`0.1.0` -> `1.1.0`)
for the macOS `.dmg` embedded version ONLY; Linux `.deb`/`.rpm` and Windows `.msi`
keep the honest `toJpackageVersion` (`0.1.0`). The embedded version therefore
deliberately DIVERGES across operating systems for the same build. Separately, the
canonical artifact FILENAME (`SkillBill-<project.version>-<os>-<arch>.<ext>`) uses
the full, un-stripped `project.version` uniformly across all operating systems.

**Decision.** The artifact **FILENAME** is the single source of truth for an
installer's version and for artifact resolution in subtask 3/4. Verifiers and
release tooling MUST resolve on the filename, never on the embedded installer
metadata — the embedded `--app-version` deliberately diverges on macOS
(`1.1.0` vs the filename's `0.1.0-SNAPSHOT`) and must not be treated as
authoritative.

**Reason.** macOS's `MAJOR >= 1` constraint forces a per-OS embedded-version
bump that the honest project version cannot satisfy, so embedded metadata is not
a stable cross-OS key. The full `project.version` in the filename is identical
across operating systems and carries the un-stripped qualifier (`-SNAPSHOT`),
making it the only consistent, honest resolution key.

**Consumers.** Subtask 3/4 artifact resolution/verification keys on the filename
token (`SkillBill-<project.version>-<os>-<arch>.<ext>`); do NOT parse the embedded
installer version.

## 2026-05-29 — Non-modular jlink images via Badass Runtime, not Badass JLink

**Context.** SKILL-55 subtask 1 needs self-contained, per-OS runtime images of
`runtime-cli` / `runtime-mcp` that run with no system JDK. The runtime modules are
plain non-modular Kotlin apps (no `module-info.java`), pulling in automatic
modules (kotlin-inject, kotlinx.serialization, jackson, networknt, sqlite-jdbc).

**Decision.** Use the Badass **Runtime** plugin (`org.beryx.runtime` 2.0.1), not
Badass **JLink** (`org.beryx.jlink`). Badass JLink requires a modular app and a
`module-info.java` — it has no non-modular path and loud-fails with "Cannot find
module-info.java". Badass Runtime is the Beryx plugin built for non-modular apps:
it links a trimmed JDK runtime with `jlink` and wraps the existing `application`
distribution, keeping the `bin/runtime-cli` / `bin/runtime-mcp` launchers. We pin
the link toolchain to Java 21 (matching `build-logic` `Jvm.kt` `JDK_VERSION`), set
an explicit `additive` module set (java.base/logging/management/naming/net.http/
sql/xml/desktop, jdk.crypto.ec, jdk.unsupported) instead of relying on jdeps (which
cannot resolve the automatic modules cleanly), and trim with `--strip-debug
--no-header-files --no-man-pages --compress 2`. `java.net.http` is required by the
telemetry HTTP client (`runtime-infra-http`), which the version/stdio smoke test
does not exercise. Image name/zip derive from `project.version` + a canonical
`<os>-<arch>` host token defined once, as a typed contract, in the
`skillbill.runtime-image` convention plugin
(`build-logic/convention/.../buildlogic/RuntimeTargets.kt`). The Badass Runtime tasks are not
configuration-cache compatible, so they opt out per-task via
`notCompatibleWithConfigurationCache`; the global config cache stays warm for
`check` / `installDist`.

**Reason.** GraalVM `native-image` was rejected: the reflection/serialization
surface of kotlin-inject + kotlinx.serialization + jackson + sqlite-jdbc would
require extensive reachability metadata and per-OS native toolchains for little
payoff over a trimmed jlink image. A hand-rolled `jlink`+`jpackage` script was
rejected to avoid re-implementing module resolution, launcher generation, and
per-OS zipping that Badass Runtime already provides. Badass JLink (the plan's
first choice) was rejected because it fundamentally cannot link a non-modular app.

## 2026-05-24 — Runtime paths stay inert outside adapters and composition

**Context.** SKILL-52.1 tightened hexagonal boundaries while several public
application/domain/port models still need to carry `java.nio.file.Path` values
for caller-provided homes, repo roots, and generated plan locations.

**Decision.** Keep `Path` legal as inert data in application/domain/port public
models, but ban filesystem IO, home expansion, process environment reads, and
system-property reads outside adapters or composition.

**Reason.** Replacing every path with strings would make typed runtime contracts
weaker, while allowing `Path` operations that touch the host would leak adapter
responsibilities back into domain and port code.

## 2026-05-24 — Preserve dual install-plan validation after policy extraction

**Context.** SKILL-52.1 moved install planning toward typed policy and
capability ports, but install-plan wire maps still cross two independent
emission seams: builder output and CLI JSON emission.

**Decision.** Keep the shared install-plan wire-snapshot validator at both the
builder seam and CLI emission seam after the refactor.

**Reason.** The builder proves the pure plan shape, while CLI emission can still
assemble or project a payload after planning; validating both seams preserves
the existing loud-fail contract instead of relying on one earlier check.

## 2026-05-24 — Runtime-core retains only generated DI public ABI edges

**Context.** The runtime-core shrink makes the module a composition root rather
than an implementation umbrella, but Kotlin-Inject generated components expose
some application service and port types in the public `RuntimeComponent` ABI.

**Decision.** Retain only the generated Kotlin-Inject public ABI edges required
by `RuntimeComponent`: direct API edges to runtime-application services and
runtime-ports context/port types, with the documented transitive domain and
contracts closure, and no infrastructure or entrypoint API edges.

**Reason.** Hiding the generated DI ABI would fight the toolchain and break
callers, but documenting and testing the narrow edge prevents runtime-core from
growing back into a compatibility umbrella.

## 2026-05-18 — Platform-pack manifest validation moves to a canonical JSON Schema

**Context.** Before SKILL-47 the rules describing
`platform-packs/<slug>/platform.yaml` lived only inside
`ShellContentLoader.buildPack` (Kotlin parser code), `ScaffoldSupport.kt`
(`SHELL_CONTRACT_VERSION`, `APPROVED_CODE_REVIEW_AREAS`, `CONTENT_BODY_FILENAME`),
and the in-memory `PlatformManifest` data class. No standalone document
described the manifest shape; new fields drifted across three files with no
mechanical link, and the desktop UI had nowhere to render a contract reference.

**Decision.** Adopt JSON Schema (Draft 2020-12) authored as YAML at
`orchestration/contracts/platform-pack-schema.yaml` as the source of truth for
the manifest shape. Validate manifests against the schema at runtime through
`com.networknt:json-schema-validator` (full Draft 2020-12 support, Apache-2.0)
bridged via Jackson `databind` (already required transitively by the validator).
The parser still produces the existing `PlatformManifest`; only the shape-rule
source moves. Cross-field coherence rules (`slug-parity`,
`areas-require-baseline`, `areas-equal-declared`,
`area-metadata-keys-subset-declared`, `pointers-unique-name-per-dir`) stay in
Kotlin because they are awkward to express in pure JSON Schema, but each is
named and documented in the schema file's `x-coherence-checks` block so the
schema document alone describes the full contract.

**Alternatives considered.**

- *Keep rules in Kotlin (status quo).* Rejected: drift across data model,
  parser, and `SHELL_CONTRACT_VERSION` is the problem this task solves.
- *Custom YAML-with-our-own-validator DSL.* Rejected: low leverage, every new
  rule needs custom validator code, no tooling ecosystem.
- *kaml + Kotlin data classes as schema.* Rejected: still couples schema to
  runtime code, no documentation surface, no UI viewer.

**Consequences.**

- Adds two runtime dependencies to `runtime-core`:
  `com.networknt:json-schema-validator` and Jackson `databind` /
  `dataformat-yaml`. Pure-JVM, no native bindings, no reflection magic.
- `SHELL_CONTRACT_VERSION` is pinned to the schema's `contract_version.const`
  via a parity test. Mismatch is a build break, not a runtime mystery.
- Desktop UI can surface the canonical schema file as a read-only viewer
  through the existing editor pane; no second copy of the schema lives in the
  UI module.
- Wrapping the validator behind `PlatformPackSchemaValidator` keeps the
  library choice local — swapping it later means rewriting one Kotlin file.

## 2026-05-19 — Install-plan validates at BOTH builder and CLI seams (diverges from 2a)

**Context.** SKILL-48 subtask 2a (workflow-state) wired schema validation at a
single seam — the canonical `Canonical*` parse path — and relied on that one
choke-point to keep the wire honest. Subtask 2b (install-plan) explicitly
specifies dual-seam validation in AC4: both `buildInstallPlan` (in
`runtime-core`'s `InstallPlanBuilder`) and `installPlanPayload` (in
`runtime-cli`'s `InstallCliPayloads.kt`) must validate the install-plan-shaped
map against the canonical schema and loud-fail via
`InvalidInstallPlanSchemaError`.

**Decision.** Keep `InstallPlanSchemaValidator.validate(...)` calls at both
seams. The CLI seam is not a redundant safety net — it covers post-build
re-assembly that the builder cannot see (the CLI may stitch additional fields
in before emission), and AC4 of subtask 2b
(`.feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2b_install-plan.md`)
explicitly requires both seams to loud-fail. Diverging from the 2a single-seam
pattern is intentional for install-plan.

**Consequences.**

- The CLI-side `installPlanPayload` carries a code comment naming AC4 so
  future readers do not mistake the dual validation for accidental duplication.
- Tests under `runtime-domain` exercise the validator in isolation; the
  CLI-side coverage flows through existing CLI integration tests.
- Deferred decision: the install-plan validator currently ships as a Kotlin
  `object` singleton (`InstallPlanSchemaValidator`) rather than the 2a
  `interface + Canonical*` shape. This is acceptable while the validator has a
  single in-process consumer; revisit (lift to an interface + canonical impl)
  when a second consumer needs to substitute a fake.

**Superseded by 2026-05-28 (SKILL-52.3).** The dual-seam INTENT (validate at
both the builder seam and the CLI emission seam) still holds, but the mechanics
described above are stale: neither seam may import `InstallPlanSchemaValidator`
directly, the validator no longer lives in `runtime-core`/`runtime-domain`
(it moved to `runtime-infra-fs`), and both seams now validate through the
injected domain-owned `InstallPlanWireValidator` port (the CLI seam routes via
the thin application method `InstallService.validateInstallPlanWire`). See the
2026-05-28 entry for the relocation and the 2026-05-29 external-schema entry for
the source-of-truth and parity guarantee.

## 2026-05-28 — Schema validators move from runtime-contracts to runtime-infra-fs, reached through domain ports

**Context.** SKILL-52.3 closes the runtime hexagon leak: the foundational
`runtime-contracts` leaf owned three networknt + Jackson + filesystem schema
validators (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator` /
`CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`)
plus the `DecompositionManifestCoherenceValidator`, and `runtime-domain` install
policy invoked the concrete install-plan validator at runtime. A contract leaf
and the domain should not own infrastructure-grade schema loading.

**Decision.** Move all three schema validators and the coherence validator into
`runtime-infra-fs` — the module that already owns `PlatformPackSchemaValidator`
and `NativeAgentCompositionSchemaValidator`. Reach them only through
domain-owned ports that generalize the existing `WorkflowSnapshotValidator`
pattern: `InstallPlanWireValidator` (runtime-domain `skillbill.install.model`)
and `DecompositionManifestValidator` (runtime-domain `skillbill.workflow`).
Wire each port to an infra-fs adapter through `RuntimeComponent` with
`@Provides @JvmSynthetic internal`, exactly like every other infra adapter.
The pure `*SchemaPaths` and `*_CONTRACT_VERSION` constants stay in
`runtime-contracts`; the networknt + Jackson dependencies and the three schema
`Copy` tasks move with the validators to `runtime-infra-fs`. The library choice
is unchanged.

**Reason.** Keeping `Path`-free constants in contracts preserves the single
source of truth for schema locations while removing infrastructure ownership
from the contract leaf and the domain. Routing every validator through a
domain-owned port keeps the three validators reached uniformly and lets the
composition root own the concrete wiring, so `runtime-domain`'s runtime closure
no longer pulls networknt/Jackson transitively.

**Supersedes.**

- 2026-05-24 "Preserve dual install-plan validation after policy extraction" —
  dual-seam coverage (builder + CLI emission) is preserved, but neither seam may
  live inside `runtime-domain`; both now validate through the injected
  `InstallPlanWireValidator` port.
- 2026-05-18 "Platform-pack manifest validation moves to a canonical JSON
  Schema" added the validator dependencies to `runtime-core`; they later moved
  to `runtime-contracts`. This subtask moves all schema validators to
  `runtime-infra-fs`, the module that already owns the platform-pack validator.

**Note.** The infra-side adapters live in `runtime-infra-fs`, not
`runtime-application`, because the application layer cannot depend on infra
without inverting the hexagon. The former `runtime-application`
`WorkflowSnapshotValidatorAdapter` is superseded by
`WorkflowSnapshotValidatorInfraAdapter`. Final source-of-truth wording for the
schema files themselves is recorded in the 2026-05-29 external-schema entry
below (subtask 5).

---

## 2026-05-29 — External schemas are the source of truth, copied into the runtime at build time (SKILL-52.3 subtask 5)

Context: Each runtime contract schema (`install-plan`, `workflow-state`,
`decomposition-manifest`, `platform-pack`, `native-agent-composition`,
`telemetry-event`) is authored once as Draft 2020-12 YAML under
`../orchestration/contracts/`, OUTSIDE the Gradle project, and consumed at
runtime as a classpath resource by the JVM validators.

Decision: Keep `orchestration/contracts/*.yaml` as the single canonical source
of truth. `runtime-infra-fs` copies the five schema files
(`copyInstallPlanSchema`, `copyWorkflowStateSchema`,
`copyDecompositionManifestSchema`, `copyPlatformPackSchema`,
`copyNativeAgentCompositionSchema`) and `runtime-mcp` copies the sixth
(`copyTelemetryEventSchema`) into their generated resources at build time. Each
`Copy` task is config-cache-safe: the canonical source path is captured as a
plain `String` `val` at configuration time and fed to `from(...)` /
`inputs.file(...)` (no `Project`/`Task` reference is captured), while only the
`require(File(path).exists())` existence check runs inside a `doFirst {}`
guard, loud-failing with a named message if the canonical file is missing. Parity is mechanical: every
`*_CONTRACT_VERSION` constant in `runtime-contracts` (or the domain/mcp
equivalents) is pinned to its schema's `properties.contract_version.const` by a
dedicated `*SchemaContractVersionTest`, so bumping one without the other is a
build break.

Reason: The schemas are shared with the orchestration layer (CLI/MCP tooling),
so they cannot live inside one Gradle module without forking the contract.
Copying at build time keeps the runtime self-contained (validators load a
classpath resource, not a repo-relative path) while preserving the external
file as the one place a contract change is made. The loud-fail guard turns a
missing-schema misconfiguration into an immediate, named build failure instead
of a runtime `null` resource stream.

Revisit when: a schema needs to diverge between the runtime and the
orchestration tooling, or when the runtime is published as a standalone
artifact without access to `../orchestration/contracts/`.

## 2026-05-29 — SKILL-52.3 subtask 4: application wire seam + open-boundary reconciliation

**Decisions.**

1. **Type `SystemService.doctor` / `version`.** Both now return
   `DoctorContract` / `VersionContract`; the CLI (`SystemCliCommands`) and MCP
   (`McpRuntime`) adapters own the `.toPayload()` call. Output stays
   byte-equivalent. The two FQNs were removed from the raw-map allow-list, the
   ARCHITECTURE.md open-boundary block, and the SKILL-52.2 `must_type_now`
   inventory group.

2. **Relabel lifecycle payloads + `LifecycleTelemetryService` as permanent open
   boundaries.** The 5 `LifecycleTelemetryPayloads` helpers and the 7
   `LifecycleTelemetryService` emit methods are forward-compatible MCP/CLI event
   bags with no stable per-key schema, so they are now annotated
   `@OpenBoundaryMap` and moved from the SKILL-52.2 `postponed_with_reason`
   group (gated, `[subtask 4]`) into `open_extension` (no subtask tag) rather
   than typed away. No event names, keys, shapes, or persisted payloads changed.
   All "will remove" / future-tense removal wording was deleted from
   ARCHITECTURE.md and `RuntimeArchitectureTest`.

**Encode-seam relocation rationale.** YAML serialization for the decomposition
manifest moved out of `runtime-application` (`DecompositionManifestFileWrites`)
behind a new `DecompositionManifestFileStore.encodeManifestYaml(wireMap)` port
method, implemented by the infra-fs `FileSystemDecompositionManifestFileStore`
with the same `YAMLMapper()` construction (byte-identical output). This mirrors
the subtask-1 decode seam (`DecompositionManifestValidator`): the application
layer keeps `encodeDecompositionManifestMap` (the validated-map builder) and
still calls `validator.validateYamlText` AFTER serialization, so the write path
keeps throwing `InvalidDecompositionManifestSchemaError` on invalid input.
`runtime-application` main no longer imports Jackson and its build no longer
carries the production `jackson.dataformat.yaml` dependency (relocated to
`testImplementation` for the pre-existing + new test doubles). The new port
method is `@OpenBoundaryMap`-annotated and documented in the allow-list +
`open_extension` inventory because the raw-map architecture scanner walks
`runtime-ports`.

## 2026-06-04 — Goal telemetry: writes on LifecycleTelemetryRepository, goalStats() on WorkflowStatsRepository

**Context.** SKILL-66 Subtask 2 adds persistence for the goal telemetry event
family (`goal_started`, `goal_subtask_finished`, `goal_finished`). Acceptance
criterion 1 reads literally as "`LifecycleTelemetryRepository` gains methods for
the three goal events ... plus the read/aggregate queries needed for stats", which
could be read as putting the aggregate read on the same port. But every existing
lifecycle family keeps writes on `LifecycleTelemetryRepository` (write-only:
`featureImplementStarted`, `featureVerifyStarted`, `featureTaskRuntimeStarted`,
...) and puts the aggregate read on `WorkflowStatsRepository`
(`featureImplementStats()`, `featureVerifyStats()`, `featureTaskRuntimeStats()`).

**Decision.** Goal **writes** (`goalStarted`/`goalSubtaskFinished`/`goalFinished`)
go on `LifecycleTelemetryRepository`; the aggregate **read** `goalStats()` goes on
`WorkflowStatsRepository`. AC#1's own tiebreaker clause — "*following the interface
style of the existing event methods*" — selects parity placement over literal
single-port grouping. No existing family reads through
`LifecycleTelemetryRepository`, and breaking that would split the read surface
across two ports.

**Reason.** Parity keeps the stats surface single-sourced on
`WorkflowStatsRepository` (which Subtask 4's `goal_stats` tool reads), preserves
the established write/read seam separation, and avoids leaking a read method onto
the write-only telemetry port. The cost is that AC#1's literal "on
`LifecycleTelemetryRepository`" wording is satisfied for writes only; the
read lives one port over, exactly as `featureTaskRuntimeStats()` does.

**Consumers.** Subtask 3 calls the three write methods from `GoalRunner`;
Subtask 4 reads `goalStats()` for the `goal_stats` MCP tool and `goal-stats` CLI.

## 2026-06-05 — Goal runtime telemetry: loud-fail, per-segment run-session id, and resume dedup (SKILL-66 Subtask 3)

**Context.** SKILL-66 Subtask 3 wires goal lifecycle emission
(`goal_started`/`goal_subtask_finished`/`goal_finished`) into `GoalRunner`. Four
decisions had to be settled: how the runtime distinguishes per-segment run
sessions from stable per-subtask children, how a resumed run avoids
double-counting, what `attempt_count` means, and how a telemetry write failure is
handled relative to the best-effort observability/ledger writes that surround it.

**Decisions.**

1. **Loud-fail, NOT best-effort.** Goal telemetry flows through a new
   application seam `GoalLifecycleTelemetryEmitter`, implemented by
   `LifecycleTelemetryService` via the existing `enabledStandaloneResult ->
   database.transaction` path. When telemetry is **enabled**, a repository write
   that throws propagates out of the emitter and out of `GoalRunner.run`, failing
   the run (AC4, parent AC5). It is deliberately NOT wrapped in `runCatching`
   like `GoalRunnerObservabilityEmitter`/`GoalRunnerLedgerRecorder`, whose writes
   are best-effort by design. When telemetry is **disabled** the seam is a silent
   no-op (no write, no throw), preserving the disabled-vs-enabled-failure
   distinction. The default `GoalLifecycleTelemetryEmitter.NONE` keeps emission
   purely additive so non-telemetry runs stay byte-equivalent (parent AC8).

2. **(D1) Per-segment run-session `workflow_id`.** `goal_started`/`goal_finished`
   carry `"<parentWorkflowId>:seg:<segmentStartedAt>"`, where `segmentStartedAt`
   is captured once at loop start from the injected clock. It is deterministic
   under a fake clock, unique per segment (the clock advances between resume
   segments), and can never collide with the stable child `wfl-N` ids (which
   never contain `:seg:`). This is what makes "exactly one per run segment" hold
   across resumes.

3. **(D2 + resume dedup) `goal_subtask_finished.workflow_id` = stable child id.**
   Each `goal_subtask_finished` carries the subtask's durable child workflow id
   (`wfl-N`); a never-launched terminal (a projection-driven skip) falls back to
   a stable `"<issueKey>:subtask:<id>"`. Combined with the persistence-layer
   dedup key `(issue_key, subtask_id, workflow_id)` (Subtask 2's
   `ON CONFLICT DO NOTHING`), a subtask contributes at most one terminal event
   across all segments. The runtime also snapshots `priorTerminal` (ids already
   terminal at loop start) and only emits for subtasks reaching terminal status
   *within the current segment*, so a resumed run never re-emits earlier
   segments' work even before the DB dedup applies.

4. **(D4) `attempt_count` is runtime-owned and per-segment.** It is the number of
   times the subtask id appears in the runner-owned in-memory `attempted` list,
   coerced to at least 1. Under the current one-attempt-per-subtask-per-segment
   loop it resolves to 1; cross-segment accumulation is out of scope and the
   dedup above prevents inflation. The child-progress `attemptCount` (reflects
   child *step* retries, nullable, costs an extra read) and the durable ledger
   (no per-subtask attempt count) were both rejected.

5. **(D5) Centralized transition-detector for terminal emission.** A single
   `sweepTerminal` pass over the manifest after each iteration (and once before
   `goal_finished`) emits for each newly-terminal subtask. This uniformly covers
   `complete`, `blocked`, AND `skipped` — the last is set only by external
   manifest projection, never by the loop, so no per-emit-site hook could catch
   it. `goal_finished` subtask counts are computed independently from the final
   manifest (`count { status == ... }`), not from any merged report field.

**Reason.** Telemetry that silently drops writes would make the goal stats
surface (Subtask 4) untrustworthy, so the write failure is loud; the
observability/ledger streams remain best-effort because they are diagnostic, not
the metric of record. Splitting the per-segment session id from the per-subtask
child id is what lets "exactly one per segment" and "never double-count on
resume" both hold without a stateful cross-segment counter.

**Consumers.** Subtask 4 stats expectations: `goal_subtask_finished` dedupes by
`(issue_key, subtask_id, child workflow_id)`; `goal_started`/`goal_finished` are
per-segment (distinct `:seg:` ids) and stats group by `issue_key`;
`attempt_count` is per-segment (1 today).

## 2026-07-05 — pack skills internalize by flattening into one parent; baseline co-presence is loud-fail (SKILL-104)

Context: SKILL-102's internal-skill mechanism deliberately loud-failed `internal-for` on
platform-pack skills. The code-review family (34 stack skills across ios/kotlin/kmp/python) needs
the same hiding treatment, but pack skills are discovered, selected, staged, and hashed through a
selection-gated pipeline distinct from base skills.

Decision: Three Pinned Decisions shape the extension. **PD1** keeps the single shared evaluator
(`InternalSkillClassification.kt`) and relaxes ONLY the base-skill-only rule — every other rule
(blank value, self parent, unknown parent, parent must be a listed base skill, depth is 1) is
byte-for-byte unchanged; the `isBaseSkill` flag now feeds only the parent-side rule. **PD2**
flattens: all 34 sidecars are siblings inside `bill-code-review`'s staged directory (depth stays
1; nesting would require a sidecar-hosting-sidecar the staging model cannot express). **PD3**
makes sidecar discovery selection-aware: `discoverInternalSidecarTargets` accepts the plan's
selected pack skills and unions them with the skills-root scan, so an unselected pack contributes
no sidecar and no hash bytes (inertness — a repo with no opted-in pack skill stages
byte-identically). **PD8** adds a plan-time guard (`MissingBaselinePlatformSelectionError`) that
loud-fails when a selected pack declares a required `baseline_layers` entry in an unselected
pack; the shell never silently auto-includes a baseline.

Reason: A `platform.yaml`-level "internal" flag would fork a second classification source and
desynchronize the three seams (authoring, install-plan, validate); PD1's whole point is one
evaluator. Selection-aware staging is the only way to honor pack selection (hidden skills from
unselected packs must not ship) without breaking cache reuse — the parent's content hash folds
only the selected sidecars, so changing selection re-stages. The PD8 guard is pinned here, not
deferred, because today's behavior (selecting KMP alone silently installs a review whose baseline
is absent) becomes load-bearing once the baseline is a sidecar.

Trade-off: Pack sidecar discovery is source-aware (it consults `InstallPlanSkill.sourceDir` from
the plan, not an independent re-scan of `platform-packs/`), so the three staging seams (plan
builder, apply, link-skill fallback) each thread the selected pack skills. The link-skill flow
refuses internal skills upstream and never reaches the pack-sidecar path.

## 2026-08-10 — Runtime-owned validate gate (SKILL-180)

**Decision.** Validate-phase build/test/gate execution moves to the runtime via pack-declared
`validation_gate` argv. The agent receives a bounded finding projection and must not invoke the
gate or quality-check skills. Terminal satisfaction requires a forced-full pack-declared run with
non-zero executed work. Missing gate declarations degrade to agent-run validate with a surfaced
observability record at `ValidationGateResolver.resolve`.

**Boundary.** Validation owns execution; audit and repair evidence remain read-only repository
facts agents read but do not produce by running builds or tests outside validate's runtime-owned
gate cycle.

