package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.ProduceMissingPlansArgs
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepLaunchPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.repository.RepositoryEnclosingRootPort
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
  checkpointPort: GoalPlanningSweepCheckpointPort,
  launchPort: GoalPlanningSweepLaunchPort,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) : GoalPlanningSweep {
  val checkpoint = checkpointPort.checkpoint
  val outputValidator = checkpointPort.outputValidator
  val invariantsSource = checkpointPort.invariantsSource
  val manifestFileStore = checkpointPort.manifestFileStore
  val contextDiscovery = checkpointPort.contextDiscovery
  val planningProjectionValidator = checkpointPort.planningProjectionValidator
  val subtaskLauncher = launchPort.subtaskLauncher
  val manifestStore = launchPort.manifestStore
  val planningAttemptRecorder = launchPort.planningAttemptRecorder
  val planningRejectionRecorder = launchPort.planningRejectionRecorder
  val timingPort = launchPort.timingPort
  val fanOutPort = launchPort.fanOutPort
  val burstSchedule = launchPort.burstSchedule
  val refreshLiveness = launchPort.refreshLiveness

  override fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome {
    val identity = GoalPlanningIdentity(
      state.parentWorkflowId,
      state.manifest.issueKey.trim().uppercase(),
      "repo-root-realpath-v1:${canonicalRepository(request.repoRoot, repositoryEnclosingRootPort)}",
    )
    val existingShared = runCatching { checkpoint.findSharedPreplan(identity, request.dbPathOverride) }
      .getOrElse { error ->
        return preSweepStopped(request, preparationStateReadReason(error, request.issueKey, 0))
      }
    val recoveredPacket = existingShared?.let(::planningPacketFrom)
    if (existingShared != null && recoveredPacket == null) {
      return preSweepStopped(
        request,
        goalPlanningMissingSharedContextPacketStopReason(
          request.issueKey,
          goalPlanningRemedySubtaskId(state.manifest.subtasks),
        ),
      )
    }
    val gathered = runCatching { gatherSharedContext(this, state, request, recoveredPacket) }
      .getOrElse { error -> return preSweepStopped(request, sharedContextReason(error)) }
    return continueAfterSharedContext(state, request, identity, existingShared, gathered)
  }

  private fun continueAfterSharedContext(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    identity: GoalPlanningIdentity,
    existingShared: SharedGoalPreplanCheckpoint?,
    gathered: GoalPlanningSharedContext,
  ): GoalPlanningSweepOutcome {
    var shared = gathered
    val activeSubtasks = state.manifest.subtasks.filter {
      it.id in GoalPlanningSharedContextPacket.includedSubtaskIds(shared.planningPacket)
    }
    return when (
      val settled = settleSharedPreplan(
        SharedPreplanSettlementArgs(
          existingShared = existingShared,
          currentProvenance = currentProvenance(shared),
          shared = shared,
          state = state,
          request = request,
          identity = identity,
        ),
      )
    ) {
      is SharedPreplanSettlement.Halt -> settled.outcome
      is SharedPreplanSettlement.Ready -> {
        shared = settled.shared
        if (activeSubtasks.isEmpty()) {
          GoalPlanningSweepOutcome.PreparedAll(identity, settled.provenance)
        } else {
          produceMissingPlans(
            ProduceMissingPlansArgs(
              shared = shared,
              request = request,
              identity = identity,
              provenance = settled.provenance,
              sharedCheckpoint = settled.checkpoint,
              activeSubtasks = activeSubtasks,
            ),
          )
        }
      }
    }
  }
}
