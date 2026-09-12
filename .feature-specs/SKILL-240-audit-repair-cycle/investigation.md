# SKILL-240 investigation and repair plan

Investigated on 2026-09-12 against feature HEAD 4bf1d04797337b86e6b21460cd8585cb24a9f546 and the uncommitted repair changes present after runtime termination. This is source investigation, not a passing audit or quality-check receipt.

The latest durable audit reported subtask AC-002 through AC-007. Those numbers refer to the original subtask, not the newly numbered criteria in the replacement subtasks. Parent acceptance criteria retain their existing numbering and intended outcome.

## Findings by original criterion

| Original subtask AC | Current finding | Repair owner |
| --- | --- | --- |
| 002, same session | Initial provider binding cannot pass its own ownership precondition. SQLite replay validation still falls through an inherited no-op. Provider resume and stream handling need boundary tests. | Subtask 1 |
| 003, final checkpoint | The isolated index fixes shared-index capture, but capture returns a tree ID while the pending revision expects a different fingerprint. Empty repair scope fails to retain dirty implementation content. | Subtask 2 |
| 004, final settlement | Ordinary settlement now compares final assessment values, but carried-forward settlement omits that comparison. Missing run invariants still disable the required-cycle check. | Subtask 2 |
| 005, recovery | Restored evidence now includes checkpoint fingerprints and pending validation. Recovery still composes stale ownership into prompts, has no read-only reconciliation operation, and cannot leave several paused stages. | Subtask 1 for recovery, subtask 2 for Git reconciliation |
| 006, no progress | New pauses now permit an operator decision, but authorization and grant consumption remain separate transactions. Revision numbers substitute for repair rounds. | Subtask 1 |
| 007, status and telemetry | Latest-assessment projection improved. Status still reads separate snapshots, and stage writes still lack transactionally durable telemetry. | Subtask 3 |

## AC-002: launch identity and replay

FeatureTaskRuntimeRunLoopLaunch.executeSubtaskLaunch now installs a provider-session callback before launching. However, AuditRepairCycleStore.recordProviderSession calls assertOwnership with its default binding checks. That method rejects a binding whose providerSessionId is null. A newly created binding necessarily has a null provider identity, so the first callback cannot persist it.

Split bootstrap ownership verification from stage authorization. Bootstrap must prove the workflow, active lease and exact launch binding without requiring the field it is about to populate. Subsequent recording must be idempotent for the same provider ID and reject a replacement. Diagnosis and repair authorization must require the completed binding.

AuditRepairCycleRepository.validateStageOwner has a default no-op, and SqliteAuditRepairCycleRepository does not override it. FeatureTaskPhaseSettlementService.auditStage calls it before returning a replay acknowledgement, but that call does not validate the production worker lease. Make validation mandatory and perform replay validation under the same transaction snapshot as lookup.

ProcessAgentRunAdapter.providerSessionCapturingSink matches session_id or thread_id anywhere in buffered stdout with a regex. It can match payload content, repeats the callback on later chunks, and has a fixed capture limit. Use provider-owned structured event decoding, with one durable callback for the actual session-start identity. Require delivery before stage access. Callback failure must become a typed launch failure with normal child cleanup.

CodexAgentRunCommandBuilder currently emits codex resume followed by --json. The installed CLI identifies that command as interactive; codex exec resume is the noninteractive command with JSON output. Check the complete command grammar and working-directory handling. Claude's streaming selection does not force streaming for audit repair, which can delay identity until process completion. Junie explicitly rejects resume; unsupported continuation must have a durable, actionable outcome.

## AC-003: checkpoint identity

GitCheckpointHistoryOperations now uses an isolated GIT_INDEX_FILE, read-tree, add, write-tree and commit-tree. A temporary-repository probe confirmed that this preserves HEAD and the user's index and excludes an unrelated staged change from capture. Keep that improvement.

The complete path is inconsistent. CHECKPOINT_PENDING verifies GitWorkflowGitOperationsFingerprint.repositoryFingerprint, a SHA-256 over HEAD, staged and unstaged diffs, and untracked content. capturePostRepair returns currentScopedContentFingerprint, a Git tree object ID. validateAuditRepairTransition requires the attached checkpoint fingerprint to equal the pending fingerprint. The probe produced a 40-character tree ID versus the 64-character runtime digest. These are different identities regardless of digest length.

Persist an engine-owned scope and content identity before capture. Use that identity through intent, capture, retained verification, final audit and review handoff. Preserve the broader activity fingerprint for diagnostics where needed, but do not compare it with a content ID.

Capture derives scope only from agent-reported repair outcomes. An initially satisfied audit has no outcomes even when implementation left dirty files. The empty-path branch returns HEAD paired with the current dirty-state fingerprint; HEAD does not retain those changes. Scope must include all runtime-owned implementation changes and authorized repairs, including deletions and new files.

verifyRetained still checks only commit existence. Existing-ref recovery ignores the expected fingerprint on its successful fast path, and an empty scope compares the current fingerprint with itself. Capture must return an immutable commit identity and validate retained content against the persisted intent. A retained ref must not silently be rebound to newer content under the same intent.

The edited capturePostRepair block also ends with an expression without an explicit return. Treat source compilability as a prerequisite, not evidence of behavioral completion.

## AC-004: one final eligibility decision

auditRepairReady can compare expectedValue with the final durable assessment. Ordinary tool settlement and envelope ingestion now supply it. FeatureTaskRuntimeRunLoopDrive.settleCarriedForwardAudit still passes no value and hard-codes attempt 1. It can also record the carried output before eligibility is checked. Both fast and restored paths must resolve the owning cycle and compare the exact final value before recording completion.

DurableFeatureTaskRuntimeAcceptanceCriteriaSource.cycleRequired returns false when run invariants are absent. Absence during active execution must lead to typed recovery, not legacy success. Completed compatible legacy records must remain readable without requiring the current worktree to match an old audit.

The review transition now checks readiness before and after creating its checkpoint. Preserve both checks and make them use stable content identity, so message-only amendments pass while changed reviewable content fails. Preserve pending validation obligations through every settlement path.

## AC-005: interruption and recovery

prepareLaunch composes the stage channel before executeSubtaskLaunch rebinds the current lease. The prompt prefers the old binding's owner token and fencing generation. Restore and rebind the cycle before composing any request metadata.

findActive now includes satisfied cycles, which fixes the earlier exclusion but selects the latest attempt rather than an explicitly identified owning cycle. Recovery needs an authoritative cycle identity and settlement state. A satisfied cycle awaiting envelope persistence must settle without another repair.

restoredCycleEvidence includes more fields now, but concatenates arbitrary prose using commas and vertical bars. Use the versioned codec or a bounded structured projection so punctuation in evidence cannot corrupt recovery input.

There is no operation to reconcile edits made before a repair receipt. PAUSED can authorize repair only when it contains a failed final assessment and no repair outcomes. Pauses from diagnosis, partial repair or checkpoint capture cannot follow that path. Define recovery for every stage, preserve producer attribution, and attach retained checkpoints without repeating completed mutations.

## AC-006: atomic progress and operator decisions

recordPause now writes grantConsumed=false, and failed final PAUSED requests now call it. Both earlier defects are partially repaired.

withRetryGrant now runs the cycle mutation before persisting consumption. That reverses the crash window rather than closing it. A crash can commit authorization without consuming its grant. Cycle advance, progress, pause publication and retry consumption need one fenced SQLite transaction. Replay must return the existing acknowledgement without consuming another grant or clearing a later operator decision.

The pause artifact's edgeIteration is populated from a revision number. A cycle contains several revisions per repair round. Use actual repair-round accounting and existing progress policy. Do not introduce a new arbitrary iteration cap.

The existing main-line progress policy deliberately permits a changed repository with unchanged gap IDs. A separate main-line defect treated an unavailable current fingerprint as a change and blocked a missing previous fingerprint even when criteria improved. Main commit eb6c2ad5d uses a strict reduction of unresolved ACs as a recorded fallback when either fingerprint is unavailable. It does not establish final audit eligibility.

## AC-007: consistent status and durable events

FeatureTaskRuntimeStatusService reads phase records, ledger, pause and cycle separately. A concurrent pause or recovery can produce a status that never existed in the database. Add a bounded read operation using one SQLite snapshot and project the result through feature-task and goal status.

acknowledgeStage writes only a diagnostics warning. FeatureTaskRuntimeLifecycleTelemetryEmission reads cycle data at run completion and suppresses failures with getOrNull. Persist payload-free transition, pause and recovery events in the same transaction as the corresponding state change. Replays must not duplicate events. A failed outbox write must roll back the transition, and failed delivery must retry from the outbox without replaying the state mutation.

The cycle-only status branch still reports firstPassConvergence=false and auditGapIterationCount=0 regardless of actual rounds. Keep definitions consistent when legacy progress artifacts are absent. Preserve latest unresolved ACs, execution attribution, checkpoint identity and distinct audit failure identity.

## Why existing tests missed the gaps

AuditRepairCycleStoreTest creates simplified workflow and lease tables, then starts cycles without creating launch bindings. It does not exercise the initial provider-session precondition. AuditRepairSettlementTest largely advances an in-memory repository directly and uses the unavailable checkpoint coordinator, whose verification methods return true. Those tests protect useful model rules but do not prove the production launch, stage, Git and SQLite path.

Each replacement subtask includes a small number of boundary regressions. The final gate must include a real temporary Git repository and production SQLite adapter through stage acknowledgements, plus interruption before and after durable boundaries. A mocked acknowledgement or git diff --check is not sufficient evidence.

## Evidence and limits

Inspected production call paths, domain transitions, schema and codec, persistence adapter, DI bindings, CLI commands, and existing tests. Ran isolated Git primitive probes and local CLI help checks. No feature implementation files were changed during this investigation, and no SKILL-240 runtime test or quality gate was claimed as passed.

The original child is wftr-20260912-111118-r9i0. Its frozen plan and original AC census must not be reused for the narrowed subtask 1. See the parent spec's execution-state adoption requirements.

## Source Locations

Line numbers describe the investigated working tree and may move during repair.

| Criterion | Source | Line |
| --- | --- | --- |
| AC-002 | [AuditRepairCycleStore.kt](../../runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/AuditRepairCycleStore.kt) | 182 |
| AC-002 | [AuditRepairCycleRepository.kt](../../runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/featuretask/AuditRepairCycleRepository.kt) | 32 |
| AC-002 | [SqliteAuditRepairCycleRepository.kt](../../runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/SqliteAuditRepairCycleRepository.kt) | 12 |
| AC-002 | [AgentRunAdapters.kt](../../runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/launcher/agentrun/AgentRunAdapters.kt) | 168 |
| AC-003 | [AuditRepairCheckpointCoordinator.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/AuditRepairCheckpointCoordinator.kt) | 67 |
| AC-003 | [GitCheckpointHistoryOperations.kt](../../runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/GitCheckpointHistoryOperations.kt) | 9 |
| AC-003 | [GitWorkflowGitOperationsFingerprint.kt](../../runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/GitWorkflowGitOperationsFingerprint.kt) | 25 |
| AC-003/005 | [AuditRepairCycleTransitions.kt](../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/AuditRepairCycleTransitions.kt) | 3 |
| AC-004 | [FeatureTaskRuntimeRunLoopDrive.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeRunLoopDrive.kt) | 195 |
| AC-004 | [FeatureTaskRuntimeRunLoopTransitions.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeRunLoopTransitions.kt) | 109 |
| AC-004/006 | [FeatureTaskRuntimeAcceptanceCriteriaSource.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeAcceptanceCriteriaSource.kt) | 8 |
| AC-005 | [FeatureTaskRuntimeRunLoopOutputPersistence.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeRunLoopOutputPersistence.kt) | 303 |
| AC-005 | [FeatureTaskRuntimeRunLoopLaunch.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeRunLoopLaunch.kt) | 260 |
| AC-006/007 | [FeatureTaskPhaseSettlementService.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskPhaseSettlementService.kt) | 182 |
| AC-006 | [FeatureTaskRuntimeGateProgressRecorder.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeGateProgressRecorder.kt) | 74 |
| AC-007 | [FeatureTaskRuntimeStatusService.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeStatusService.kt) | 93 |
| AC-007 | [FeatureTaskRuntimeLifecycleTelemetryEmission.kt](../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/FeatureTaskRuntimeLifecycleTelemetryEmission.kt) | 109 |
| Adoption | [WorkflowGoalRunnerManifestLoader.kt](../../runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/goalrunner/WorkflowGoalRunnerManifestLoader.kt) | 153 |

## Main-Line Fix Validation

Main commit eb6c2ad5d adds the recorded AC-reduction fallback for unavailable fingerprints and fixes the operator pause wording. The Kotlin pack collect-all command passed in an isolated local clone with the exact main patch. The audit progress regression suite reports 10 tests, zero failures and zero skips. The full test reports contain 5062 tests, zero failures or errors, and 3 skips. The initial worktree check could not configure Spotless because its Git lookup did not recognize the worktree; the local clone avoided that environment limitation. This result validates the main fix, not the unfinished SKILL-240 implementation.
