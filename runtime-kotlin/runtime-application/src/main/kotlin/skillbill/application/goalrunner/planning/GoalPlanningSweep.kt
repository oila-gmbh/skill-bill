package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Path

fun interface GoalPlanningSweep {
  fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome

  companion object {
    val NONE: GoalPlanningSweep = GoalPlanningSweep { _, _ -> GoalPlanningSweepOutcome.PreparedAll() }
  }
}

internal data class GoalPlanningSharedContext(
  val issueKey: String,
  val normalizedIssueKey: String,
  val parentWorkflowId: String,
  val manifest: DecompositionManifest,
  val controlState: GoalRunnerControlState,
  val repositoryIdentity: String,
  val parentSpec: String,
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val dbPathOverride: String?,
  val repoRoot: Path,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val specSource: SpecSource,
  val parentSpecPath: Path,
  val planningPacket: Map<String, Any?>,
)

@Inject
class DefaultGoalPlanningSweep(
  internal val checkpoint: GoalPlanningPreparationCheckpoint,
  internal val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  internal val subtaskLauncher: GoalRunnerSubtaskLauncher,
  internal val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  internal val manifestFileStore: DecompositionManifestFileStore,
  internal val contextDiscovery: GoalPlanningContextDiscovery,
  internal val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  internal val planningAttemptRecorder: GoalPlanningAttemptRecorder = GoalPlanningAttemptRecorder.NONE,
  internal val manifestStore: GoalRunnerManifestStore,
  internal val planningRejectionRecorder: GoalPlanningRejectionRecorder = GoalPlanningRejectionRecorder.NONE,
  internal val timingPort: RuntimeTimingPort = NoopRuntimeTimingPort,
  internal val burstSchedule: GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(),
  internal val refreshLiveness: GoalPlanningRefreshLiveness = GoalPlanningRefreshLiveness.IDLE,
) : GoalPlanningSweep {
  @Suppress("ReturnCount")
  override fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome {
    val canonicalRepository = canonicalRepository(request.repoRoot)
    val identity = GoalPlanningIdentity(
      state.parentWorkflowId,
      state.manifest.issueKey.trim().uppercase(),
      "repo-root-realpath-v1:$canonicalRepository",
    )
    val existingShared = runCatching { checkpoint.findSharedPreplan(identity, request.dbPathOverride) }
      .getOrElse { error ->
        return preSweepStopped(
          request,
          preparationStateReadReason(error, request.issueKey, 0),
        )
      }
    val recoveredPacket = existingShared?.let(::planningPacketFrom)
    if (existingShared != null && recoveredPacket == null) {
      val remedySubtaskId = goalPlanningRemedySubtaskId(state.manifest.subtasks)
      return preSweepStopped(
        request,
        goalPlanningMissingSharedContextPacketStopReason(request.issueKey, remedySubtaskId),
      )
    }
    var shared = runCatching { gatherSharedContext(state, request, recoveredPacket) }.getOrElse { error ->
      return preSweepStopped(request, sharedContextReason(error))
    }
    val activeSubtasks = state.manifest.subtasks.filter {
      it.id in GoalPlanningSharedContextPacket.includedSubtaskIds(shared.planningPacket)
    }
    val currentProvenance = currentProvenance(shared)
    when (
      val settled = settleSharedPreplan(
        existingShared = existingShared,
        currentProvenance = currentProvenance,
        shared = shared,
        state = state,
        request = request,
        identity = identity,
      )
    ) {
      is SharedPreplanSettlement.Halt -> return settled.outcome
      is SharedPreplanSettlement.Ready -> {
        shared = settled.shared
        if (activeSubtasks.isEmpty()) {
          return GoalPlanningSweepOutcome.PreparedAll(identity, settled.provenance)
        }
        return produceMissingPlans(
          shared = shared,
          request = request,
          identity = identity,
          provenance = settled.provenance,
          sharedCheckpoint = settled.checkpoint,
          activeSubtasks = activeSubtasks,
        )
      }
    }
  }
}
