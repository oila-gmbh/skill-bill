package skillbill.application.goalrunner.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
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

@Suppress("ReturnCount")
internal fun DefaultGoalPlanningSweep.settleSharedPreplan(
  existingShared: SharedGoalPreplanCheckpoint?,
  currentProvenance: GoalPlanningContractProvenance,
  shared: GoalPlanningSharedContext,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  identity: GoalPlanningIdentity,
): SharedPreplanSettlement {
  var working = shared
  val (provenance, sharedCheckpoint) = when (
    val recoverability = classifyRecoverability(existingShared, currentProvenance, working)
  ) {
    is GoalPlanningProvenanceRecoverability.Irrecoverable ->
      return SharedPreplanSettlement.Halt(incompatibleProvenance(working, recoverability.recoveryKind))
    is GoalPlanningProvenanceRecoverability.Reuse -> {
      val settled = existingShared ?: produceSharedPreplan(working, request, recoverability.provenance)
        .getOrElse { error ->
          return SharedPreplanSettlement.Halt(
            stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN),
          )
        }
      recoverability.provenance to settled
    }
    is GoalPlanningProvenanceRecoverability.StaleValid -> {
      return settleStaleValidSharedPreplan(
        existingShared = requireNotNull(existingShared),
        currentProvenance = currentProvenance,
        shared = working,
        state = state,
        request = request,
        identity = identity,
        refreshedThisPrepare = false,
      )
    }
  }
  return SharedPreplanSettlement.Ready(provenance, sharedCheckpoint, working)
}

@Suppress("ReturnCount")
internal fun DefaultGoalPlanningSweep.settleStaleValidSharedPreplan(
  existingShared: SharedGoalPreplanCheckpoint,
  currentProvenance: GoalPlanningContractProvenance,
  shared: GoalPlanningSharedContext,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  identity: GoalPlanningIdentity,
  refreshedThisPrepare: Boolean,
): SharedPreplanSettlement {
  var working = shared
  var alreadyRefreshed = refreshedThisPrepare
  val first = refreshStaleSharedPreplan(
    existing = existingShared,
    shared = working,
    state = state,
    request = request,
    currentProvenance = currentProvenance,
    refreshedThisPrepare = alreadyRefreshed,
  ).getOrElse { error ->
    return SharedPreplanSettlement.Halt(
      when (error) {
        is RefreshRefused -> stopped(working, 0, error.reason, GoalPlanningSweepConstants.PHASE_PREPLAN)
        else -> stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN)
      },
    )
  }
  alreadyRefreshed = true
  val afterRefresh = runCatching {
    checkpoint.findSharedPreplan(identity, request.dbPathOverride)
  }.getOrElse { error ->
    return SharedPreplanSettlement.Halt(
      preSweepStopped(
        request,
        preparationStateReadReason(error, request.issueKey, 0),
      ),
    )
  }
    ?: first.checkpoint
  val afterPacket = planningPacketFrom(afterRefresh) ?: working.planningPacket
  working = working.copy(planningPacket = afterPacket)
  val (provenance, sharedCheckpoint) = when (
    val second = classifyRecoverability(afterRefresh, currentProvenance, working)
  ) {
    is GoalPlanningProvenanceRecoverability.Irrecoverable ->
      return SharedPreplanSettlement.Halt(incompatibleProvenance(working, second.recoveryKind))
    is GoalPlanningProvenanceRecoverability.Reuse -> second.provenance to afterRefresh
    is GoalPlanningProvenanceRecoverability.StaleValid -> {
      refreshStaleSharedPreplan(
        existing = afterRefresh,
        shared = working,
        state = state,
        request = request,
        currentProvenance = currentProvenance,
        refreshedThisPrepare = alreadyRefreshed,
      ).getOrElse { error ->
        return SharedPreplanSettlement.Halt(
          when (error) {
            is RefreshRefused -> stopped(working, 0, error.reason, GoalPlanningSweepConstants.PHASE_PREPLAN)
            else -> stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN)
          },
        )
      }.let { it.provenance to it.checkpoint }
    }
  }
  return SharedPreplanSettlement.Ready(provenance, sharedCheckpoint, working)
}

internal fun DefaultGoalPlanningSweep.currentProvenance(shared: GoalPlanningSharedContext) = GoalPlanningContractProvenance(
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

@Suppress("ReturnCount")
internal fun DefaultGoalPlanningSweep.refreshStaleSharedPreplan(
  existing: SharedGoalPreplanCheckpoint,
  shared: GoalPlanningSharedContext,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  currentProvenance: GoalPlanningContractProvenance,
  refreshedThisPrepare: Boolean,
): Result<RefreshedSharedPreplan> = runCatching {
  if (refreshedThisPrepare) {
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

internal fun DefaultGoalPlanningSweep.incompatibleProvenance(
  shared: GoalPlanningSharedContext,
  kind: GoalPlanningRecoveryKind,
): GoalPlanningSweepOutcome.Stopped = stopped(
  shared,
  0,
  goalPlanningIncompatibleProvenanceStopReason(
    shared.issueKey,
    goalPlanningRemedySubtaskId(shared.manifest.subtasks),
    kind,
  ),
  GoalPlanningSweepConstants.PHASE_PREPLAN,
)

@Suppress("ReturnCount")
internal fun DefaultGoalPlanningSweep.produceSharedPreplan(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  provenance: GoalPlanningContractProvenance,
): Result<SharedGoalPreplanCheckpoint> =
  produceSharedPreplanCheckpoint(shared, request, provenance).mapCatching { produced ->
    produced.also { checkpoint.recheckpointSharedPreplan(it, shared.dbPathOverride) }
  }

internal fun DefaultGoalPlanningSweep.produceSharedPreplanCheckpoint(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  provenance: GoalPlanningContractProvenance,
): Result<SharedGoalPreplanCheckpoint> = runCatching {
  val runInvariants = invariantsSource.read(shared.parentSpecPath)
  val preplanProduction = producePhase(shared, request, null, runInvariants, GoalPlanningSweepConstants.PHASE_PREPLAN, emptyList()) { raw ->
    enrichPreplan(raw, shared.planningPacket)
  }
  if (preplanProduction is GoalPlanningPhaseProduction.Stopped) error(preplanProduction.outcome.blockedReason)
  val captured = preplanProduction as GoalPlanningPhaseProduction.Captured
  val preplanPayload = captured.payload
  SharedGoalPreplanCheckpoint(
    identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
    provenance = provenance,
    payloadSha256 = sha256HexUtf8(preplanPayload),
    preplanPayload = preplanPayload,
    repairEvidence = captured.repairEvidence,
  )
}

internal fun DefaultGoalPlanningSweep.gatherSharedContext(
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  recoveredPacket: Map<String, Any?>?,
): GoalPlanningSharedContext {
  val canonicalRepository = canonicalRepository(request.repoRoot)
  val parentSpecGoverningPath = state.manifest.parentSpecPath
  val manifestGoverningPath = parentSpecGoverningPath.substringBeforeLast("/") + "/" + DECOMPOSITION_MANIFEST_FILENAME
  val resolvedParentSpecPath = resolvedGovernedPath(canonicalRepository, parentSpecGoverningPath)
  val parentSpec = manifestFileStore.readText(resolvedParentSpecPath)
  val decomposition = manifestFileStore.readText(resolvedGovernedPath(canonicalRepository, manifestGoverningPath))
  val parentSpecHash = sha256HexUtf8(parentSpec)
  val decompositionManifestHash = GoalPlanningSharedContextPacket.immutableDecompositionHash(state.manifest)
  val repositoryIdentity = "repo-root-realpath-v1:$canonicalRepository"
  val planningPacket = recoveredPacket?.let(GoalPlanningSharedContextPacket::migrate)
    ?: contextDiscovery.discover(canonicalRepository).let { discovered ->
      val packet = linkedMapOf<String, Any?>(
        "packet_version" to GoalPlanningSharedContextPacket.VERSION,
        "repository_identity" to repositoryIdentity,
        "normalized_issue_key" to state.manifest.issueKey.trim().uppercase(),
        "parent_spec_path" to parentSpecGoverningPath,
        "parent_spec" to parentSpec.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        "decomposition_manifest" to decomposition.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        "boundary_memory" to GoalPlanningSharedContextPacket.catalog(discovered),
        "validation_guidance" to discovered.validationGuidance.take(
          GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS,
        ),
        "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(state.manifest.subtasks),
      )
      packet + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(packet))
    }
  GoalPlanningSharedContextPacket.validate(
    packet = planningPacket,
    repositoryIdentity = repositoryIdentity,
    normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
    parentSpecPath = parentSpecGoverningPath,
    subtasks = state.manifest.subtasks,
  )
  return GoalPlanningSharedContext(
    issueKey = request.issueKey,
    normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
    parentWorkflowId = state.parentWorkflowId,
    manifest = state.manifest,
    controlState = state.controlState,
    repositoryIdentity = repositoryIdentity,
    parentSpec = parentSpec,
    parentSpecHash = parentSpecHash,
    decompositionManifestHash = decompositionManifestHash,
    dbPathOverride = request.dbPathOverride,
    repoRoot = canonicalRepository,
    invokedAgentId = request.invokedAgentId,
    configuredAgentOverrideId = request.configuredAgentOverrideId,
    specSource = state.manifest.specSource,
    parentSpecPath = resolvedParentSpecPath,
    planningPacket = planningPacket,
  )
}

internal fun enrichPreplan(payload: String, packet: Map<String, Any?>): String {
  val root = JsonSupport.parseObjectOrNull(payload)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: error("preplan payload is not a JSON object")
  val produced = JsonSupport.anyToStringAnyMap(root["produced_outputs"])
    ?: error("preplan produced_outputs is not an object")
  return JsonSupport.mapToJsonString(
    root + ("produced_outputs" to (produced + (GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD to packet))),
  )
}

internal fun planningPacketFrom(record: SharedGoalPreplanCheckpoint): Map<String, Any?>? =
  JsonSupport.parseObjectOrNull(record.preplanPayload)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get(GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD)
    ?.let(JsonSupport::anyToStringAnyMap)
