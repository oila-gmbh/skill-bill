package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator
import java.time.Clock

private class FeatureTaskRuntimePhaseRecorderParts(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  quarantineValidator: FeatureTaskRuntimeQuarantineValidator,
  implementationAttemptValidator: FeatureTaskRuntimeImplementationAttemptValidator,
  rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
  diagnostics: RuntimeDiagnostics,
  clock: Clock,
) {
  val workflowPersistence = FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator)
  val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  val rejectedOutput = FeatureTaskRuntimeRejectedOutputRecorder(
    database,
    workflowPersistence,
    rejectedOutputDiagnosticMetadataValidator,
    producerOutputEvidenceValidator,
    clock,
  )
  val phaseState = FeatureTaskRuntimePhaseStateRecorder(
    database,
    workflowPersistence,
    runtimeOwnedPersistence,
    implementationAttemptValidator,
    clock,
  )
  val reviewCheckpoint = FeatureTaskRuntimeReviewCheckpointRecorder(
    database,
    workflowPersistence,
    runtimeOwnedPersistence,
  )
  val goalReviewCompletion = FeatureTaskRuntimeGoalReviewCompletionRecorder(database, workflowPersistence, clock)
  val briefingRecorder = FeatureTaskRuntimePhaseBriefingRecorder(
    database,
    workflowPersistence,
    handoffEnvelopeValidator,
    handoffFoundationValidator,
  )
  val gateProgress = FeatureTaskRuntimeGateProgressRecorder(database, workflowPersistence)
  val evidence = FeatureTaskRuntimePhaseEvidenceRecorder(
    database,
    workflowPersistence,
    quarantineValidator,
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
  constructor(
    database: DatabaseSessionFactory,
    workflowSnapshotValidator: WorkflowSnapshotValidator,
    handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
    handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
    quarantineValidator: FeatureTaskRuntimeQuarantineValidator,
    implementationAttemptValidator: FeatureTaskRuntimeImplementationAttemptValidator,
    rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
    producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
    diagnostics: RuntimeDiagnostics,
    clock: Clock,
  ) : this(
    FeatureTaskRuntimePhaseRecorderParts(
      database = database,
      workflowSnapshotValidator = workflowSnapshotValidator,
      handoffEnvelopeValidator = handoffEnvelopeValidator,
      handoffFoundationValidator = handoffFoundationValidator,
      quarantineValidator = quarantineValidator,
      implementationAttemptValidator = implementationAttemptValidator,
      rejectedOutputDiagnosticMetadataValidator = rejectedOutputDiagnosticMetadataValidator,
      producerOutputEvidenceValidator = producerOutputEvidenceValidator,
      diagnostics = diagnostics,
      clock = clock,
    ),
  )
}
