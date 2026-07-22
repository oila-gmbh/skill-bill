# SKILL-137 Subtask 7 - Delegated Review Launch Projections

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Apply least-context projections to delegated code review, where one authoritative diff fans out to many specialist lanes and every lane currently re-acquires the whole thing.

`GovernedReviewLaunch.toLaunchEnvelope()` in `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/ReviewPacketProjection.kt` projects `assigned_hunks` as content-free hunk ids, while only `toParentPacketEnvelope()` carries `changed_hunks` with `content`. A specialist therefore receives the identity of the change it must review but not the change itself, and each delivery path improvises a different recovery:

- The native broker path compensates in `DelegatedReviewWorkerLauncher.evidenceRequests`/`boundedPrompt`, which request one `ReviewEvidenceRequest(lane, path)` per assigned path and inline the complete file body under `brokered_evidence:` — unchanged code included.
- The prompt-driven path leaves acquisition undefined. `skills/bill-code-review/content.md` and the shared delegation contract state that a worker gets "assigned files/hunks" and never "unrelated diff", but no surface materializes a lane-scoped artifact, so the parent passes one shared diff path and every lane reads the complete diff.

Observed Kotlin specialist runs confirm the cost: lanes open near 21 KTok, close between 78 and 105 KTok, five lanes each admit the same complete diff, and two lanes re-read the same diff file with overlapping offsets — 12.8 KTok of content admitted as 29.5 KTok. Two lanes additionally re-read the governed rubric and `specialist-contract.md` from disk although both are already in the launch.

Deliver each lane its assigned hunk content and nothing else, on every provider path, and make whole-file or whole-diff admission an authorized expansion rather than the default.

## Acceptance Criteria

1. The governed launch envelope carries a named bounded projection of the lane's assigned hunk content — path, hunk id, old/new ranges, and hunk body — so a specialist can establish a reachable finding without acquiring any diff. Hunk bodies are resolved from the already-parsed authoritative `ReviewDiffEvidence`; no path recomputes a diff.
2. A lane's hunk projection contains only hunks named by its own `ReviewAssignment.assignedHunks`. A hunk owned by another lane, an unassigned path, or a superseded review revision is never present in the launch.
3. Brokered evidence is hunk-window scoped by default. Assigned-path admission delivers the hunk bodies plus a contract-declared context window, not the complete file body; complete-file admission requires an authorized expansion carrying a nonblank reachability reason in the expansion ledger.
4. The delivered launch replaces diff acquisition rather than supplementing it. `ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY` gains an explicit entry for reading a diff file, diff artifact, scratch diff path, or any complete-diff body, and the broker rejects it as a forbidden operation with a typed outcome naming the lane.
5. Every provider delivery path produces the same lane projection semantics. The Claude Code prompt route, the Codex native `SpawnAgent` route, and the CLI route each hand a specialist its own lane-scoped hunk evidence; none passes a shared whole-diff path, artifact id, or scratch file that resolves to the complete diff.
6. Evidence is admitted once per lane. A specialist reads its projected evidence in full on first admission; repeat reads of the same target, offset-windowed re-reads, and paginated re-reads of an already-admitted target are contract violations, not budget consumption.
7. A specialist does not read its own rubric, specialist contract, or consumer contract from disk. `orchestration/skill-classes/code-review-specialist.yaml` ceremony no longer directs the worker at a sibling `specialist-contract.md` pointer, and the governed rubric plus contract reach the worker through the launch only.
8. The packet consumer contract has one authoritative copy. The forbidden-rediscovery list, evidence-surface rules, and report structure are not simultaneously restated in the launch envelope, `orchestration/review-orchestrator/specialist-contract.md`, and generated agent ceremony; duplication is removed or proven byte-identical by parity test rather than maintained by hand.
9. Per-lane launch budgeting counts the hunk projection and brokered evidence in `max_lane_launch_bytes` and `max_lane_evidence_bytes` before launch. Overflow fails loudly through the existing `review_context_budget_exceeded` terminal outcome or splits by a contract-declared rule; it never truncates hunk bodies, drops assigned hunks, or falls back to the complete diff.
10. Fan-out cost is bounded and measured. Telemetry records per-lane delivered launch bytes, brokered evidence bytes, estimated delivered tokens, expansion counts, and the fan-out duplication factor across lanes, without recording hunk bodies, file contents, prompts, or diff text.
11. Review substance is unchanged: lane selection, baseline flattening, area overrides, add-on composition, rubric content, composition-chain attribution, the seven-finding cap, severity and confidence enums, the `F-XXX` bullet format, expansion authorization, and parent merge/deduplication all behave exactly as before.
12. Tests prove absence as well as presence: no lane launch contains a hunk outside its assignment, a complete file body without an authorized expansion, a diff-file path, a complete-diff body, or another lane's rubric; and every lane launch contains the hunk bodies it needs to cite `file:line` evidence.
13. Golden launch-envelope snapshots exist per provider path and assert the same projected fields, so a prompt-route regression cannot silently reintroduce whole-diff delivery while the native route stays compliant.
14. Governed content, delegation playbook, skill-class ceremony, review contract schema, contract version constant, parity test, and installed staging artifacts agree on the new launch shape. Generated wrappers and support pointers remain uncommitted.

## Non-Goals

- Changing specialist rubric substance, focus statements, severity taxonomy, confidence taxonomy, or the finding cap.
- Reducing lane count, dropping required baseline lanes, or weakening review coverage to save context.
- Removing `changed_hunks` content from the parent packet, which remains the orchestrator-side authority.
- Replacing the evidence broker with an agent-controlled retrieval API, or letting a specialist request context by name.
- Host-harness context that skill-bill does not own, such as provider system prompts, tool schemas, project instruction files, and user memory injected into every subagent.
- Changing parallel-lane merge policy, provenance labelling, or `bill-code-review-parallel` semantics.

## Dependency Notes

Depends on: 1.

Uses the shared projection, budget, checkpoint, and private-evidence foundation. Independent of the feature-task phase projections in subtasks 2 through 5 and of feature verification in subtask 6, so it can run in parallel with them.

## Validation Strategy

- Launch-projection tests in `ReviewPacketProjectionTest` asserting assigned hunk content presence and cross-lane hunk absence.
- `DelegatedReviewWorkerLauncher` and `FileSystemReviewEvidenceBroker` tests for hunk-window admission, complete-file rejection without expansion, and repeat-read rejection.
- Forbidden-operation tests for diff-file and complete-diff acquisition attempts.
- Byte-budget tests covering hunk projection overflow, multibyte hunk bodies, and no-truncation behavior.
- Per-provider golden launch envelopes plus a cross-path parity test.
- Telemetry privacy tests proving hunk and file bodies never reach recorded fields.
- Skill-class ceremony, delegation playbook, and specialist-contract parity/duplication tests.
- Focused `runtime-domain`, `runtime-application`, `runtime-infra-fs`, and `runtime-contracts` Gradle tests, plus `scripts/validate_agent_configs`.

## Next Path

Continue with subtask 8 after subtasks 2 through 6 are also complete.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_7_delegated-review-launch-projections.md
