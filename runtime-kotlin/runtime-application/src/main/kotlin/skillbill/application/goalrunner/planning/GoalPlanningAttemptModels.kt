package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants

internal data class GoalPlanningAttemptScope(
  val shared: GoalPlanningSharedContext,
  val phaseId: String,
  val subtask: DecompositionSubtask?,
  val attempt: Int,
)

internal data class GoalPlanningPhaseContext(
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val subtask: DecompositionSubtask?,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val phaseId: String,
  val outputSink: AgentRunOutputSink = request.outputSink,
)

internal data class GoalPlanningProduceAttemptArgs(
  val phase: GoalPlanningPhaseContext,
  val recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  val priorSchemaFailure: String? = null,
  val resolvedBodies: GoalPlanningResolvedBoundaryBodies = GoalPlanningResolvedBoundaryBodies(),
)

internal data class GoalPlanningProducePhaseArgs(
  val attempt: GoalPlanningProduceAttemptArgs,
  val finalizePayload: (String) -> String = { it },
)

internal data class GoalPlanningAttemptRecordArgs(
  val scope: GoalPlanningAttemptScope,
  val outcome: GoalProgressOutcome,
  val eventKind: GoalProgressEventKind = GoalProgressEventKind.OPERATION_COMPLETED,
)

internal data class GoalPlanningRejectionRecordArgs(
  val scope: GoalPlanningAttemptScope,
  val rule: String,
  val reason: String,
  val agentId: String,
  val rawEvidence: String,
)

internal data class SharedPreplanSettlementArgs(
  val existingShared: SharedGoalPreplanCheckpoint?,
  val currentProvenance: GoalPlanningContractProvenance,
  val shared: GoalPlanningSharedContext,
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
)

internal data class StaleSharedPreplanSettlementArgs(
  val existingShared: SharedGoalPreplanCheckpoint,
  val currentProvenance: GoalPlanningContractProvenance,
  val shared: GoalPlanningSharedContext,
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
  val refreshedThisPrepare: Boolean,
)

internal data class RefreshStaleSharedPreplanArgs(
  val existing: SharedGoalPreplanCheckpoint,
  val shared: GoalPlanningSharedContext,
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val currentProvenance: GoalPlanningContractProvenance,
  val refreshedThisPrepare: Boolean,
)
