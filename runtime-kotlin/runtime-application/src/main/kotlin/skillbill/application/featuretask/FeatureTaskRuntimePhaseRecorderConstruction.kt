package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderDeps
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderValidators
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator

fun featureTaskRuntimePhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = featureTaskRuntimePhaseRecorder(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  validators = FeatureTaskRuntimePhaseRecorderValidators(
    handoffEnvelopeValidator = handoffEnvelopeValidator,
    handoffFoundationValidator = handoffFoundationValidator,
    quarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
    implementationAttemptValidator = NoopFeatureTaskRuntimeImplementationAttemptValidator,
    rejectedOutputDiagnosticMetadataValidator = { },
    producerOutputEvidenceValidator = { },
  ),
  diagnostics = diagnostics,
)

fun featureTaskRuntimePhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  validators: FeatureTaskRuntimePhaseRecorderValidators,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = FeatureTaskRuntimePhaseRecorder(
  FeatureTaskRuntimePhaseRecorderDeps(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    validators = validators,
    diagnostics = diagnostics,
  ),
)
