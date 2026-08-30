package skillbill.application.goalrunner.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.contracts.JsonSupport
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState

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
  val preplanProduction = producePhase(
    GoalPlanningProducePhaseArgs(
      attempt = GoalPlanningProduceAttemptArgs(
        phase = GoalPlanningPhaseContext(
          shared = shared,
          request = request,
          subtask = null,
          runInvariants = runInvariants,
          phaseId = GoalPlanningSweepConstants.PHASE_PREPLAN,
        ),
        recordedOutputs = emptyList(),
      ),
      finalizePayload = { raw -> enrichPreplan(raw, shared.planningPacket) },
    ),
  )
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
  val decompositionManifestHash = goalPlanningImmutableDecompositionHash(state.manifest)
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
