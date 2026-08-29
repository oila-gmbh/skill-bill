package skillbill.application.goalrunner.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState

internal sealed class SharedPreplanSettlement {
  class Ready(
    val provenance: GoalPlanningContractProvenance,
    val checkpoint: SharedGoalPreplanCheckpoint,
    val shared: GoalPlanningSharedContext,
  ) : SharedPreplanSettlement()

  class Halt(val outcome: GoalPlanningSweepOutcome) : SharedPreplanSettlement()
}

internal data class RefreshedSharedPreplan(
  val provenance: GoalPlanningContractProvenance,
  val checkpoint: SharedGoalPreplanCheckpoint,
)

internal class RefreshRefused(val reason: String) : RuntimeException(reason)

internal fun DefaultGoalPlanningSweep.settleSharedPreplan(args: SharedPreplanSettlementArgs): SharedPreplanSettlement {
  var working = args.shared
  val (provenance, sharedCheckpoint) = when (
    val recoverability = classifyRecoverability(args.existingShared, args.currentProvenance, working)
  ) {
    is GoalPlanningProvenanceRecoverability.Irrecoverable ->
      return SharedPreplanSettlement.Halt(incompatibleProvenance(working, recoverability.recoveryKind))
    is GoalPlanningProvenanceRecoverability.Reuse -> {
      val settled = args.existingShared
        ?: produceSharedPreplan(working, args.request, recoverability.provenance)
          .getOrElse { error ->
            return SharedPreplanSettlement.Halt(
              stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN),
            )
          }
      recoverability.provenance to settled
    }
    is GoalPlanningProvenanceRecoverability.StaleValid -> {
      return settleStaleValidSharedPreplan(
        StaleSharedPreplanSettlementArgs(
          existingShared = requireNotNull(args.existingShared),
          currentProvenance = args.currentProvenance,
          shared = working,
          state = args.state,
          request = args.request,
          identity = args.identity,
          refreshedThisPrepare = false,
        ),
      )
    }
  }
  return SharedPreplanSettlement.Ready(provenance, sharedCheckpoint, working)
}

internal fun DefaultGoalPlanningSweep.settleStaleValidSharedPreplan(
  args: StaleSharedPreplanSettlementArgs,
): SharedPreplanSettlement {
  var working = args.shared
  var alreadyRefreshed = args.refreshedThisPrepare
  val first = refreshStaleSharedPreplan(
    RefreshStaleSharedPreplanArgs(
      existing = args.existingShared,
      shared = working,
      state = args.state,
      request = args.request,
      currentProvenance = args.currentProvenance,
      refreshedThisPrepare = alreadyRefreshed,
    ),
  ).getOrElse { error ->
    return SharedPreplanSettlement.Halt(refreshHaltOutcome(working, error))
  }
  alreadyRefreshed = true
  when (val loaded = loadSharedPreplanAfterRefresh(args, first)) {
    is SharedPreplanAfterRefresh.Halt -> return SharedPreplanSettlement.Halt(loaded.outcome)
    is SharedPreplanAfterRefresh.Ready -> {
      val afterPacket = planningPacketFrom(loaded.checkpoint) ?: working.planningPacket
      working = working.copy(planningPacket = afterPacket)
      return reclassifyAfterStaleRefresh(
        StaleRefreshReclassifyArgs(
          settlement = args,
          working = working,
          afterRefresh = loaded.checkpoint,
          alreadyRefreshed = alreadyRefreshed,
        ),
      )
    }
  }
}

private fun DefaultGoalPlanningSweep.refreshHaltOutcome(
  working: GoalPlanningSharedContext,
  error: Throwable,
): GoalPlanningSweepOutcome = when (error) {
  is RefreshRefused -> stopped(working, 0, error.reason, GoalPlanningSweepConstants.PHASE_PREPLAN)
  else -> stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN)
}

private sealed interface SharedPreplanAfterRefresh {
  class Ready(val checkpoint: SharedGoalPreplanCheckpoint) : SharedPreplanAfterRefresh
  class Halt(val outcome: GoalPlanningSweepOutcome) : SharedPreplanAfterRefresh
}

private fun DefaultGoalPlanningSweep.loadSharedPreplanAfterRefresh(
  args: StaleSharedPreplanSettlementArgs,
  first: RefreshedSharedPreplan,
): SharedPreplanAfterRefresh {
  val afterRefresh = runCatching {
    checkpoint.findSharedPreplan(args.identity, args.request.dbPathOverride)
  }.getOrElse { error ->
    return SharedPreplanAfterRefresh.Halt(
      preSweepStopped(
        args.request,
        preparationStateReadReason(error, args.request.issueKey, 0),
      ),
    )
  }
  return SharedPreplanAfterRefresh.Ready(afterRefresh ?: first.checkpoint)
}

private data class StaleRefreshReclassifyArgs(
  val settlement: StaleSharedPreplanSettlementArgs,
  val working: GoalPlanningSharedContext,
  val afterRefresh: SharedGoalPreplanCheckpoint,
  val alreadyRefreshed: Boolean,
)

private fun DefaultGoalPlanningSweep.reclassifyAfterStaleRefresh(
  args: StaleRefreshReclassifyArgs,
): SharedPreplanSettlement {
  val working = args.working
  val afterRefresh = args.afterRefresh
  return when (
    val second = classifyRecoverability(afterRefresh, args.settlement.currentProvenance, working)
  ) {
    is GoalPlanningProvenanceRecoverability.Irrecoverable ->
      SharedPreplanSettlement.Halt(incompatibleProvenance(working, second.recoveryKind))
    is GoalPlanningProvenanceRecoverability.Reuse ->
      SharedPreplanSettlement.Ready(second.provenance, afterRefresh, working)
    is GoalPlanningProvenanceRecoverability.StaleValid -> {
      refreshStaleSharedPreplan(
        RefreshStaleSharedPreplanArgs(
          existing = afterRefresh,
          shared = working,
          state = args.settlement.state,
          request = args.settlement.request,
          currentProvenance = args.settlement.currentProvenance,
          refreshedThisPrepare = args.alreadyRefreshed,
        ),
      ).fold(
        onSuccess = { SharedPreplanSettlement.Ready(it.provenance, it.checkpoint, working) },
        onFailure = { error -> SharedPreplanSettlement.Halt(refreshHaltOutcome(working, error)) },
      )
    }
  }
}

internal fun DefaultGoalPlanningSweep.currentProvenance(shared: GoalPlanningSharedContext) =
  GoalPlanningContractProvenance(
    shared.parentSpecHash,
    shared.decompositionManifestHash,
    GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
  )

internal fun DefaultGoalPlanningSweep.classifyRecoverability(
  existing: SharedGoalPreplanCheckpoint?,
  current: GoalPlanningContractProvenance,
  shared: GoalPlanningSharedContext,
): GoalPlanningProvenanceRecoverability {
  if (existing == null) {
    return GoalPlanningProvenanceRecoverability.Reuse(current)
  }
  val packetParentSpec = shared.planningPacket["parent_spec"] as? String
  val savedParentSpec = if (existing.provenance.parentSpecHash == shared.parentSpecHash) {
    shared.parentSpec
  } else {
    packetParentSpec
  }
  return classifyGoalPlanningProvenanceRecoverability(
    existing = existing,
    current = current,
    savedParentSpec = savedParentSpec,
    currentParentSpec = shared.parentSpec,
  )
}

internal fun DefaultGoalPlanningSweep.refreshStaleSharedPreplan(
  args: RefreshStaleSharedPreplanArgs,
): Result<RefreshedSharedPreplan> = runCatching {
  val existing = args.existing
  val shared = args.shared
  val state = args.state
  val request = args.request
  val currentProvenance = args.currentProvenance
  if (args.refreshedThisPrepare) {
    return@runCatching RefreshedSharedPreplan(existing.provenance, existing)
  }
  refuseRefreshReason(shared.issueKey, refreshLiveness.resolve(state, shared.dbPathOverride))?.let { reason ->
    throw RefreshRefused(reason)
  }
  val refreshShared = shared.copy(planningPacket = freshPlanningPacket(shared, state))
  val produced = produceSharedPreplanCheckpoint(refreshShared, request, currentProvenance)
    .getOrElse { throw it }
  val savedValueHash = preplanProseValueHash(existing.preplanPayload)
  val newValueHash = preplanProseValueHash(produced.preplanPayload)
  val savedPromptHash = preplanProsePromptHash(existing.preplanPayload)
  val newPromptHash = preplanProsePromptHash(produced.preplanPayload)
  if (savedValueHash == newValueHash && savedPromptHash == newPromptHash) {
    checkpoint.sharedPreplanRefresh.advanceSharedPreplanProvenance(
      identity = existing.identity,
      expectedPayloadSha256 = existing.payloadSha256,
      provenance = currentProvenance,
      dbOverride = shared.dbPathOverride,
    )
    val advanced = existing.copy(provenance = currentProvenance)
    RefreshedSharedPreplan(currentProvenance, advanced)
  } else {
    val cascadeIds = cascadeEligiblePlanSubtaskIds(
      plannedIds = checkpoint.sharedPreplanRefresh.listPreparedPlanSubtaskIds(
        state.parentWorkflowId,
        shared.dbPathOverride,
      ),
      subtasks = state.manifest.subtasks,
    )
    val replaced = checkpoint.sharedPreplanRefresh.replaceSharedPreplanForRefresh(
      checkpoint = produced,
      expectedPayloadSha256 = existing.payloadSha256,
      cascadePlanSubtaskIds = cascadeIds,
      dbOverride = shared.dbPathOverride,
    )
    RefreshedSharedPreplan(currentProvenance, replaced)
  }
}

internal fun DefaultGoalPlanningSweep.freshPlanningPacket(
  shared: GoalPlanningSharedContext,
  state: GoalRunnerManifestState,
): Map<String, Any?> {
  val discovered = contextDiscovery.discover(shared.repoRoot)
  val decomposition = manifestFileStore.readText(
    resolvedGovernedPath(
      shared.repoRoot,
      state.manifest.parentSpecPath.substringBeforeLast("/") + "/" + DECOMPOSITION_MANIFEST_FILENAME,
    ),
  )
  val packet = linkedMapOf<String, Any?>(
    "packet_version" to GoalPlanningSharedContextPacket.VERSION,
    "repository_identity" to shared.repositoryIdentity,
    "normalized_issue_key" to shared.normalizedIssueKey,
    "parent_spec_path" to state.manifest.parentSpecPath,
    "parent_spec" to shared.parentSpec.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
    "decomposition_manifest" to decomposition.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
    "boundary_memory" to GoalPlanningSharedContextPacket.catalog(discovered),
    "validation_guidance" to discovered.validationGuidance.take(
      GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS,
    ),
    "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(state.manifest.subtasks),
  )
  return packet + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(packet))
}
