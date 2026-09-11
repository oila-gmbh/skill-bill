package skillbill.engine.featuretask.model

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.time.Clock

@Inject
data class FeatureTaskRuntimeRunnerDependencies(
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
  val diagnostics: RuntimeDiagnostics,
  val clock: Clock,
)
