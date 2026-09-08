package skillbill.application.featuretask

import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator
import java.time.Clock

fun featureTaskRuntimePhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = FeatureTaskRuntimePhaseRecorder(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  handoffEnvelopeValidator = handoffEnvelopeValidator,
  handoffFoundationValidator = handoffFoundationValidator,
  quarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
  implementationAttemptValidator = NoopFeatureTaskRuntimeImplementationAttemptValidator,
  rejectedOutputDiagnosticMetadataValidator = { },
  producerOutputEvidenceValidator = { },
  diagnostics = diagnostics,
  clock = clock,
)
