#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / (
    "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask"
)

RUN_LOOP = ROOT / "FeatureTaskRuntimeRunLoop.kt"
SHARED = ROOT / "FeatureTaskRuntimeRunLoopSharedArgs.kt"
CHECKPOINT_PREP = ROOT / "CheckpointScopePreparation.kt"
EXECUTE = ROOT / "FeatureTaskRuntimeRunnerExecutePrepared.kt"
PHASE_ATTEMPTS = ROOT / "FeatureTaskRuntimeRunLoopPhaseAttempts.kt"
CHECKPOINT = ROOT / "FeatureTaskRuntimeRunLoopCheckpoint.kt"

DRIVE_METHODS = [
    "resumedReentry",
    "invalidateReviewGenerationIfNeeded",
    "loadMigratedAuditGapPause",
    "resolveAuditGapPauseDriveAction",
    "validateAuditGapResumeOrBlock",
    "runPhaseDriveLoop",
    "phaseEntryBlockReason",
    "blockAt",
    "carriedForwardGoalReviewSettlement",
    "settleCarriedForwardAuditGapAudit",
    "advancePhaseReason",
    "settleAdvanceOutcome",
    "applyAuditGapPauseDecision",
    "entryGateBlockReason",
    "capExhaustedOnResume",
    "reconcileCompletedGoalReviewPass",
    "isCompletedGoalReview",
    "reconcileReservedGoalReviewPass",
    "reviewedCheckpointFingerprint",
    "isGoalContinuationRun",
]


def add_block_in_phase_to_phase_attempts() -> None:
    block_fn = """
  internal fun blockInPhase(runLoop: FeatureTaskRuntimeRunLoop, request: PhaseBlockRequest): PhaseOutcome =
    blockAndPersistInPhase(
      runLoop,
      phaseBlockArgs(
        request.run,
        request.attemptCount,
        request.reason,
        request.observability,
        request.payload,
      ).withDisposition(request.failureDisposition),
    )
"""
    text = PHASE_ATTEMPTS.read_text()
    if "fun blockInPhase(" in text:
        return
    text = text.replace("\n}\n", block_fn + "\n}\n", 1)
    PHASE_ATTEMPTS.write_text(text)
    shared = SHARED.read_text()
    shared = re.sub(
        r"\ninternal fun FeatureTaskRuntimeRunLoop\.blockInPhase.*?^\)\n",
        "\n",
        shared,
        flags=re.M | re.S,
    )
    SHARED.write_text(shared)


def move_checkpoint_scope_prep() -> None:
    prep_text = CHECKPOINT_PREP.read_text()
    funcs = re.findall(
        r"(internal fun FeatureTaskRuntimeRunLoop\.\w+\(.*?\n\})",
        prep_text,
        flags=re.S,
    )
    if not funcs:
        return
    checkpoint_text = CHECKPOINT.read_text()
    for func in funcs:
        transformed = func.replace(
            "internal fun FeatureTaskRuntimeRunLoop.",
            "internal fun ",
        )
        transformed = re.sub(
            r"internal fun (\w+)\(",
            r"internal fun \1(runLoop: FeatureTaskRuntimeRunLoop, ",
            transformed,
            count=1,
        )
        transformed = transform_runloop_refs(transformed)
        if transformed not in checkpoint_text:
            checkpoint_text = checkpoint_text.replace(
                "\n}\n",
                "\n\n" + "  " + transformed.replace("\n", "\n  ") + "\n}\n",
                1,
            )
    CHECKPOINT.write_text(checkpoint_text)
    for func in funcs:
        prep_text = prep_text.replace(func, "")
    CHECKPOINT_PREP.write_text(prep_text)


def transform_runloop_refs(body: str) -> str:
    props = [
        "request",
        "recorder",
        "phaseGates",
        "session",
        "state",
        "observability",
        "collaborators",
    ]
    for prop in props:
        body = re.sub(rf"(?<![.\w]){prop}\.", f"runLoop.{prop}.", body)
        body = re.sub(rf"(?<![.\w]){prop}\b(?!\s*[:=])", f"runLoop.{prop}", body)
    body = re.sub(
        r"(?<![.\w])checkpointWorktreeDelta\(",
        "runLoop.collaborators.checkpoint.checkpointWorktreeDelta(runLoop, ",
        body,
    )
    body = re.sub(
        r"(?<![.\w])blockCheckpointScope\(",
        "runLoop.collaborators.checkpoint.blockCheckpointScope(runLoop, ",
        body,
    )
    body = re.sub(
        r"(?<![.\w])phaseWrittenPaths\(",
        "runLoop.collaborators.checkpoint.phaseWrittenPaths(runLoop, ",
        body,
    )
    body = re.sub(
        r"(?<![.\w])writingPhaseIntroducedPaths\(",
        "runLoop.collaborators.checkpoint.writingPhaseIntroducedPaths(runLoop, ",
        body,
    )
    body = re.sub(
        r"(?<![.\w])checkpointDeletedPaths\(",
        "runLoop.collaborators.checkpoint.checkpointDeletedPaths(runLoop)",
        body,
    )
    body = re.sub(
        r"(?<![.\w])mayExtendOwnedInventory\(",
        "runLoop.collaborators.checkpoint.mayExtendOwnedInventory(runLoop, ",
        body,
    )
    return body


def fix_block_in_phase_calls() -> None:
    for path in ROOT.glob("FeatureTaskRuntimeRunLoop*.kt"):
        if path.name in {
            "FeatureTaskRuntimeRunLoop.kt",
            "FeatureTaskRuntimeRunLoopSharedArgs.kt",
            "FeatureTaskRuntimeRunLoopCollaborators.kt",
            "FeatureTaskRuntimeRunLoopSession.kt",
        }:
            continue
        text = path.read_text()
        if "PhaseAttempts" in path.name:
            text = re.sub(
                r"(?<![.\w])blockInPhase\(",
                "blockInPhase(runLoop, ",
                text,
            )
        else:
            text = re.sub(
                r"(?<![!\w])blockInPhase\(",
                "runLoop.collaborators.phaseAttempts.blockInPhase(runLoop, ",
                text,
            )
        path.write_text(text)


def update_run_loop() -> None:
    text = RUN_LOOP.read_text()
    facade_start = text.index("  internal val phaseContentIdentities")
    facade_end = text.index("  init {")
    text = text[:facade_start] + text[facade_end:]

    text = text.replace(
        "session.pendingReentry = resumedReentry()",
        "session.pendingReentry = collaborators.drive.resumedReentry(this)",
    )
    drive_body = """  fun drive() {
    collaborators.drive.invalidateReviewGenerationIfNeeded(this)
    collaborators.drive.loadMigratedAuditGapPause(this)?.let { pause ->
      if (collaborators.drive.resolveAuditGapPauseDriveAction(this, pause) == AuditGapDriveAction.Stop) return
    }
    if (!collaborators.drive.validateAuditGapResumeOrBlock(this)) return
    collaborators.drive.runPhaseDriveLoop(this)
  }

  internal fun advance(phaseId: String): PhaseSettlement {
    collaborators.drive.phaseEntryBlockReason(this, phaseId)?.let { reason ->
      collaborators.drive.blockAt(this, phaseId, reason)
      return PhaseSettlement.stop()
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(request)) {
      val carriedForward = collaborators.drive.carriedForwardGoalReviewSettlement(this)
      if (carriedForward != null) {
        return carriedForward
      }
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT && session.auditGapRetryResumePending) {
      session.auditGapRetryResumePending = false
      val carried = collaborators.drive.settleCarriedForwardAuditGapAudit(this)
      if (carried != null) return carried
    }
    val reason = collaborators.drive.advancePhaseReason(this, phaseId)
    return collaborators.drive.settleAdvanceOutcome(this, phaseId, reason)
  }

"""
    text = re.sub(
        r"  fun drive\(\) \{.*?return collaborators\.drive\.settleAdvanceOutcome\(this, phaseId, reason\)\n  \}\n\n",
        drive_body,
        text,
        flags=re.S,
    )
    text = text.replace(
        """  fun report(): FeatureTaskRuntimeRunReport {
    val branch = resolvedBranch
      ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
    return decomposed ?: paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(""",
        """  fun report(): FeatureTaskRuntimeRunReport {
    val branch = session.resolvedBranch
      ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
    return session.decomposed ?: session.paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: session.blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(""",
    )
    text = text.replace(
        "return applyAuditGapPauseDecision(auditGapPause, decision)",
        "return collaborators.drive.applyAuditGapPauseDecision(this, auditGapPause, decision)",
    )
    RUN_LOOP.write_text(text)


def update_execute_prepared() -> None:
    text = EXECUTE.read_text()
    text = text.replace(
        "collaborators = defaultFeatureTaskRuntimeRunLoopCollaborators(),",
        "collaborators = runLoopCollaborators,",
    )
    EXECUTE.write_text(text)
    runner = ROOT.parent / "featuretask" / "FeatureTaskRuntimeRunner.kt"
    runner_text = runner.read_text()
    if "runLoopCollaborators" not in runner_text:
        runner_text = runner_text.replace(
            "internal val activityStampWriter: AgentActivityStampWriter,\n) {",
            "internal val activityStampWriter: AgentActivityStampWriter,\n  internal val runLoopCollaborators: FeatureTaskRuntimeRunLoopCollaborators,\n) {",
        )
        runner.write_text(runner_text)


def main() -> None:
    add_block_in_phase_to_phase_attempts()
    move_checkpoint_scope_prep()
    fix_block_in_phase_calls()
    update_run_loop()
    update_execute_prepared()
    print("wired runloop collaborators")


if __name__ == "__main__":
    main()
