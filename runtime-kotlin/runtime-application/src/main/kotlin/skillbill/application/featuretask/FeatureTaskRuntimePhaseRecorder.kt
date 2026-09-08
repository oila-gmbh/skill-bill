package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderDeps

private class FeatureTaskRuntimePhaseRecorderParts(
  deps: FeatureTaskRuntimePhaseRecorderDeps,
) {
  private val clock = deps.clock
  val workflowPersistence =
    FeatureTaskRuntimeWorkflowPersistence(deps.database, deps.workflowSnapshotValidator)
  val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(deps.database, deps.diagnostics)
  val rejectedOutput = FeatureTaskRuntimeRejectedOutputRecorder(
    deps.database,
    workflowPersistence,
    deps.validators.rejectedOutputDiagnosticMetadataValidator,
    deps.validators.producerOutputEvidenceValidator,
    clock,
  )
  val phaseState = FeatureTaskRuntimePhaseStateRecorder(
    deps.database,
    workflowPersistence,
    runtimeOwnedPersistence,
    deps.validators.implementationAttemptValidator,
    clock,
  )
  val reviewCheckpoint = FeatureTaskRuntimeReviewCheckpointRecorder(
    deps.database,
    workflowPersistence,
    runtimeOwnedPersistence,
  )
  val goalReviewCompletion =
    FeatureTaskRuntimeGoalReviewCompletionRecorder(deps.database, workflowPersistence, clock)
  val briefingRecorder = FeatureTaskRuntimePhaseBriefingRecorder(
    deps.database,
    workflowPersistence,
    deps.validators.handoffEnvelopeValidator,
    deps.validators.handoffFoundationValidator,
  )
  val gateProgress = FeatureTaskRuntimeGateProgressRecorder(deps.database, workflowPersistence)
  val evidence = FeatureTaskRuntimePhaseEvidenceRecorder(
    deps.database,
    workflowPersistence,
    deps.validators.quarantineValidator,
    clock,
  )
}

class FeatureTaskRuntimePhaseRecorder private constructor(
  parts: FeatureTaskRuntimePhaseRecorderParts,
) : FeatureTaskRuntimePhaseWorkflowApi by parts.workflowPersistence,
  FeatureTaskRuntimePhaseRejectedApi by parts.rejectedOutput,
  FeatureTaskRuntimePhaseStateApi by parts.phaseState,
  FeatureTaskRuntimePhaseReviewApi by parts.goalReviewCompletion,
  FeatureTaskRuntimePhaseReviewCheckpointApi by parts.reviewCheckpoint,
  FeatureTaskRuntimePhaseBriefingApi by parts.briefingRecorder,
  FeatureTaskRuntimePhaseGateApi by parts.gateProgress,
  FeatureTaskRuntimePhaseEvidenceApi by parts.evidence {
  @Inject
  constructor(deps: FeatureTaskRuntimePhaseRecorderDeps) : this(FeatureTaskRuntimePhaseRecorderParts(deps))
}
