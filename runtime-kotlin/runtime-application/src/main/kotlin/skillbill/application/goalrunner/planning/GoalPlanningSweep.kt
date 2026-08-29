package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.ProduceMissingPlansArgs
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepDeps
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
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
class DefaultGoalPlanningSweep(deps: GoalPlanningSweepDeps) : GoalPlanningSweep {
  internal val checkpoint = deps.checkpoint
  internal val outputValidator = deps.outputValidator
  internal val subtaskLauncher = deps.subtaskLauncher
  internal val invariantsSource = deps.invariantsSource
  internal val manifestFileStore = deps.manifestFileStore
  internal val contextDiscovery = deps.contextDiscovery
  internal val planningProjectionValidator = deps.planningProjectionValidator
  internal val planningAttemptRecorder = deps.planningAttemptRecorder
  internal val manifestStore = deps.manifestStore
  internal val planningRejectionRecorder = deps.planningRejectionRecorder
  internal val timingPort = deps.timingPort
  internal val burstSchedule = deps.burstSchedule
  internal val refreshLiveness = deps.refreshLiveness

  override fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome {
    val identity = GoalPlanningIdentity(
      state.parentWorkflowId,
      state.manifest.issueKey.trim().uppercase(),
      "repo-root-realpath-v1:${canonicalRepository(request.repoRoot)}",
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
    val gathered = runCatching { gatherSharedContext(state, request, recoveredPacket) }
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
