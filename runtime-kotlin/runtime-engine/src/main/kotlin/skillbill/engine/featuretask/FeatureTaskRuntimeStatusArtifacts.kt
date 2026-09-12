package skillbill.engine.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.contracts.JsonCodec
import skillbill.ports.featuretask.model.AuditRepairStatusSnapshot
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap

class FeatureTaskRuntimeStatusArtifacts(snapshot: AuditRepairStatusSnapshot) {
  private val artifacts = decodeArtifacts(snapshot.artifactsJson)
  val cycle = snapshot.cycle
  val records = phaseRecordsFrom(artifacts)
  val ledger = phaseLedgerFrom(artifacts)
  val decomposeTerminal = decomposeTerminalFrom(artifacts)
  val branch = resolvedBranchFrom(artifacts)?.branch
  val qualityGateSelection = GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(artifacts)?.qualityGateSelection
  val pause = JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY])
    ?.let(FeatureTaskRuntimeAuditGapPause::fromArtifactMap)
  val validationGate = JsonCodec.anyToStringAnyMap(
    artifacts[FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY],
  )
    ?.let(FeatureTaskRuntimeValidationGateProgress::fromArtifactMap)
  val buildGate = JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY])
    ?.let(FeatureTaskRuntimeValidationGateProgress::fromArtifactMap)
  val featureSize = JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY])
    ?.let(::featureTaskRuntimeRunInvariantsFromArtifactMap)?.featureSize?.name
  val diagnosticSignals = featureTaskRuntimeDiagnosticSignalsFromWire(
    artifacts[FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY],
  )
}
