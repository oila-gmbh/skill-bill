package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderDeps
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderValidators
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.time.Clock

fun featureTaskRuntimePhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  validators: FeatureTaskRuntimePhaseRecorderValidators,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = FeatureTaskRuntimePhaseRecorder(
  FeatureTaskRuntimePhaseRecorderDeps(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    validators = validators,
    diagnostics = diagnostics,
    clock = clock,
  ),
)
