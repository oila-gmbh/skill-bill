package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.model.GoalPlanningPhaseProduction
import skillbill.application.model.GoalPlanningSweepOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path

fun interface GoalPlanningSweep {
  fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome

  companion object {
    val NONE: GoalPlanningSweep = GoalPlanningSweep { _, _ -> GoalPlanningSweepOutcome.PreparedAll }
  }
}

internal data class GoalPlanningSharedContext(
  val issueKey: String,
  val normalizedIssueKey: String,
  val parentWorkflowId: String,
  val repositoryIdentity: String,
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val dbPathOverride: String?,
  val repoRoot: Path,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val specSource: SpecSource,
  val planningPacket: Map<String, Any?>,
)

@Suppress("LongParameterList", "TooManyFunctions")
@Inject
class DefaultGoalPlanningSweep(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val manifestFileStore: DecompositionManifestFileStore,
  private val contextDiscovery: GoalPlanningContextDiscovery,
) : GoalPlanningSweep {
  @Suppress("ReturnCount")
  override fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome {
    val canonicalRepository = runCatching { request.repoRoot.toRealPath() }
      .getOrElse { request.repoRoot.toAbsolutePath().normalize() }
    val identity = GoalPlanningIdentity(
      state.parentWorkflowId,
      state.manifest.issueKey.trim().uppercase(),
      "repo-root-realpath-v1:$canonicalRepository",
    )
    val existingShared = runCatching { checkpoint.findSharedPreplan(identity, request.dbPathOverride) }
      .getOrElse { error -> return preSweepStopped(request, preparationStateReadReason(error)) }
    val recoveredPacket = existingShared?.let(::planningPacketFrom)
    if (existingShared != null && recoveredPacket == null) {
      return preSweepStopped(request, "Goal planning shared preplan does not contain a valid shared context packet.")
    }
    val shared = runCatching { gatherSharedContext(state, request, recoveredPacket) }.getOrElse { error ->
      return preSweepStopped(request, sharedContextReason(error))
    }
    val activeSubtasks = state.manifest.subtasks.filter { it.status != "skipped" }
    if (activeSubtasks.isEmpty()) return GoalPlanningSweepOutcome.PreparedAll
    val descriptors = runCatching { activeSubtasks.mapIndexed { order, subtask -> descriptor(shared, subtask, order) } }
      .getOrElse { error ->
        return stopped(
          shared,
          0,
          "Goal planning governed subtask provenance could not be computed: ${error.message.orEmpty()}",
        )
      }
    val provenance = GoalPlanningContractProvenance(
      shared.parentSpecHash,
      shared.decompositionManifestHash,
      GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
    )
    if (existingShared != null && existingShared.provenance != provenance) {
      return stopped(
        shared,
        0,
        "Goal planning shared preplan provenance is incompatible; " +
          "hard reset or explicit operator migration is required.",
      )
    }
    val sharedCheckpoint = existingShared ?: produceSharedPreplan(shared, request, activeSubtasks.first(), provenance)
      .getOrElse { error -> return stopped(shared, activeSubtasks.first().id, error.message.orEmpty(), PHASE_PREPLAN) }
    val subtasksById = activeSubtasks.associateBy(DecompositionSubtask::id)
    while (true) {
      val missingId = runCatching {
        checkpoint.recoveryProgress(identity, descriptors, shared.dbPathOverride).firstMissingSubtaskId
      }
        .getOrElse { error -> return stopped(shared, 0, preparationStateReadReason(error)) }
      if (missingId == null) return GoalPlanningSweepOutcome.PreparedAll
      val subtask = subtasksById[missingId]
        ?: return stopped(shared, missingId, noSuchSubtaskReason(missingId))
      val descriptor = descriptors.single { it.subtaskId == missingId }
      producePlan(shared, request, subtask, descriptor, provenance, sharedCheckpoint.preplanPayload)?.let { return it }
    }
  }

  @Suppress("ReturnCount")
  private fun produceSharedPreplan(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask,
    provenance: GoalPlanningContractProvenance,
  ): Result<SharedGoalPreplanCheckpoint> = runCatching {
    val resolvedSpecPath = resolvedSubSpecPath(shared.repoRoot, subtask.specPath)
      ?: error(unresolvedSpecReason(subtask))
    val runInvariants = invariantsSource.read(resolvedSpecPath)
    val preplanProduction = producePhase(shared, request, subtask, runInvariants, PHASE_PREPLAN, emptyList())
    if (preplanProduction is GoalPlanningPhaseProduction.Stopped) error(preplanProduction.outcome.blockedReason)
    val rawPreplanPayload = (preplanProduction as GoalPlanningPhaseProduction.Captured).payload
    val preplanPayload = enrichPreplan(rawPreplanPayload, shared.planningPacket)
    outputValidator.validatePhaseOutputText(preplanPayload, PHASE_PREPLAN)
    SharedGoalPreplanCheckpoint(
      identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
      provenance = provenance,
      payloadSha256 = sha256HexUtf8(preplanPayload),
      preplanPayload = preplanPayload,
    ).also { checkpoint.checkpointSharedPreplan(it, shared.dbPathOverride) }
  }

  private fun producePlan(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask,
    descriptor: GovernedGoalSubtaskDescriptor,
    provenance: GoalPlanningContractProvenance,
    preplanPayload: String,
  ): GoalPlanningSweepOutcome.Stopped? {
    val resolvedSpecPath = resolvedSubSpecPath(shared.repoRoot, subtask.specPath)
      ?: return stopped(shared, subtask.id, unresolvedSpecReason(subtask))
    val runInvariants = runCatching { invariantsSource.read(resolvedSpecPath) }.getOrElse { error ->
      return stopped(shared, subtask.id, invariantReadReason(subtask, error))
    }
    val planProduction = producePhase(
      shared,
      request,
      subtask,
      runInvariants,
      PHASE_PLAN,
      listOf(FeatureTaskRuntimePhaseOutput(PHASE_PREPLAN, 1, preplanPayload)),
    )
    if (planProduction is GoalPlanningPhaseProduction.Stopped) return planProduction.outcome
    val planPayload = (planProduction as GoalPlanningPhaseProduction.Captured).payload
    val record = GoalSubtaskPlanCheckpoint(
      identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
      subtaskId = subtask.id,
      manifestOrder = descriptor.manifestOrder,
      governedSubSpecPath = descriptor.governedSubSpecPath,
      subSpecHash = descriptor.subSpecHash,
      provenance = provenance,
      payloadSha256 = sha256HexUtf8(planPayload),
      planPayload = planPayload,
    )
    return runCatching { checkpoint.checkpointSubtaskPlan(record, shared.dbPathOverride) }.fold(
      onSuccess = { null },
      onFailure = { error -> stopped(shared, subtask.id, persistenceReason(subtask, error)) },
    )
  }

  private fun descriptor(
    shared: GoalPlanningSharedContext,
    subtask: DecompositionSubtask,
    order: Int,
  ): GovernedGoalSubtaskDescriptor {
    val path = resolvedSubSpecPath(shared.repoRoot, subtask.specPath) ?: error(unresolvedSpecReason(subtask))
    return GovernedGoalSubtaskDescriptor(
      subtask.id,
      order,
      shared.repoRoot.relativize(path).joinToString("/"),
      sha256HexUtf8(manifestFileStore.readText(path)),
    )
  }

  private fun producePhase(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    phaseId: String,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  ): GoalPlanningPhaseProduction {
    val declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, runInvariants.featureSize)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = declaration,
      runInvariants = runInvariants,
      recordedOutputs = recordedOutputs,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    val basePrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      issueKey = request.issueKey,
      briefing = briefing,
      suppressDecomposition = true,
      specSource = shared.specSource,
      specReference = runInvariants.specReference,
      agentAddonSelection = request.agentAddonSelection,
    )
    val prompt = GoalPlanningContextPromptFormatter.append(basePrompt, shared.planningPacket, subtask)
    request.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: goal planning - subtask ${subtask.id} $phaseId\n",
    )
    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = shared.invokedAgentId,
        configuredAgentOverrideId = shared.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = request.issueKey,
          repoRoot = shared.repoRoot,
          subtaskId = subtask.id,
          dbPathOverride = shared.dbPathOverride,
          timeout = request.timeout,
          progressIdleTimeout = request.progressIdleTimeout,
          outputSink = request.outputSink,
          promptOverride = prompt,
        ),
      ),
    )
    val stdout = stdoutFor(outcome)
      ?: return GoalPlanningPhaseProduction.Stopped(stopped(shared, subtask.id, exhaustedReason(outcome)))
    return runCatching { outputValidator.validateAndReadPhaseOutput(stdout, phaseId) }.fold(
      onSuccess = { payload ->
        if (payload["status"] != "completed") {
          GoalPlanningPhaseProduction.Stopped(
            stopped(shared, subtask.id, unsuccessfulStatusReason(phaseId, payload["status"])),
          )
        } else {
          GoalPlanningPhaseProduction.Captured(JsonSupport.mapToJsonString(payload))
        }
      },
      onFailure = { error ->
        GoalPlanningPhaseProduction.Stopped(stopped(shared, subtask.id, malformedReason(phaseId, error)))
      },
    )
  }

  private fun gatherSharedContext(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    recoveredPacket: Map<String, Any?>?,
  ): GoalPlanningSharedContext {
    val canonicalRepository = runCatching { request.repoRoot.toRealPath() }
      .getOrElse { request.repoRoot.toAbsolutePath().normalize() }
    val parentSpecGoverningPath = state.manifest.parentSpecPath
    val manifestGoverningPath = parentSpecGoverningPath.substringBeforeLast("/") + "/" + DECOMPOSITION_MANIFEST_FILENAME
    val parentSpec = manifestFileStore.readText(resolvedGovernedPath(canonicalRepository, parentSpecGoverningPath))
    val decomposition = manifestFileStore.readText(resolvedGovernedPath(canonicalRepository, manifestGoverningPath))
    val parentSpecHash = sha256HexUtf8(parentSpec)
    val decompositionManifestHash = immutableDecompositionHash(state.manifest)
    val repositoryIdentity = "repo-root-realpath-v1:$canonicalRepository"
    val planningPacket = recoveredPacket ?: contextDiscovery.discover(canonicalRepository).let { discovered ->
      val packet = linkedMapOf<String, Any?>(
        "packet_version" to SHARED_CONTEXT_PACKET_VERSION,
        "repository_identity" to repositoryIdentity,
        "normalized_issue_key" to state.manifest.issueKey.trim().uppercase(),
        "parent_spec_path" to parentSpecGoverningPath,
        "parent_spec" to parentSpec.take(MAX_GOVERNED_CONTEXT_CHARS),
        "decomposition_manifest" to decomposition.take(MAX_GOVERNED_CONTEXT_CHARS),
        "platform_packs" to discovered.platformPacks,
        "boundary_memory" to discovered.boundaryMemory,
        "validation_guidance" to discovered.validationGuidance.take(MAX_GOVERNED_CONTEXT_CHARS),
        "ordered_subtasks" to planningSubtasks(state.manifest.subtasks),
      )
      packet + ("integrity_sha256" to packetDigest(packet))
    }
    validatePlanningPacket(
      packet = planningPacket,
      repositoryIdentity = repositoryIdentity,
      normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
      parentSpecPath = parentSpecGoverningPath,
      parentSpec = parentSpec,
      subtasks = state.manifest.subtasks,
    )
    return GoalPlanningSharedContext(
      issueKey = request.issueKey,
      normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
      parentWorkflowId = state.parentWorkflowId,
      repositoryIdentity = repositoryIdentity,
      parentSpecHash = parentSpecHash,
      decompositionManifestHash = decompositionManifestHash,
      dbPathOverride = request.dbPathOverride,
      repoRoot = canonicalRepository,
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      specSource = state.manifest.specSource,
      planningPacket = planningPacket,
    )
  }

  private fun preSweepStopped(
    request: GoalRunnerRunRequest,
    reason: String,
    currentSubtaskId: Int = 0,
  ): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
    issueKey = request.issueKey,
    currentSubtaskId = currentSubtaskId,
    reason = GoalRunnerStopReason.BLOCKED,
    blockedReason = reason,
    lastResumableStep = PHASE_PREPLAN,
  )

  private fun sharedContextReason(error: Throwable): String =
    "Goal planning shared context could not be gathered: ${error.message.orEmpty()}"

  private fun preparationStateReadReason(error: Throwable): String =
    "Goal planning preparation state could not be read: ${error.message.orEmpty()}"

  private fun stdoutFor(outcome: AgentRunLaunchOutcome): String? = when (outcome) {
    is AgentRunLaunchFacts -> outcome.stdout.takeIf { stdout ->
      !outcome.spawnFailed &&
        !outcome.timedOut &&
        !outcome.interrupted &&
        outcome.exitStatus == 0 &&
        stdout.isNotBlank()
    }
    is UnsupportedAgentRunLaunch -> null
  }

  private fun exhaustedReason(outcome: AgentRunLaunchOutcome): String = when (outcome) {
    is UnsupportedAgentRunLaunch -> "Goal planning could not launch a planning agent: ${outcome.reason}"
    is AgentRunLaunchFacts -> "Goal planning produced no usable agent output: ${exhaustedCause(outcome)}."
  }

  private fun exhaustedCause(facts: AgentRunLaunchFacts): String = when {
    facts.spawnFailed -> "the planning agent failed to spawn"
    facts.timedOut -> "the planning agent timed out"
    facts.interrupted -> "the planning agent was interrupted"
    facts.exitStatus != null && facts.exitStatus != 0 -> "the planning agent exited with status ${facts.exitStatus}"
    else -> "the planning agent produced no usable output"
  }

  private fun malformedReason(phaseId: String, error: Throwable): String =
    "Goal planning '$phaseId' output failed the schema gate and could not be prepared: ${error.message.orEmpty()}"

  private fun unsuccessfulStatusReason(phaseId: String, status: Any?): String =
    "Goal planning '$phaseId' stopped with status '${status ?: "missing"}'; the pair was not checkpointed."

  private fun enrichPreplan(payload: String, packet: Map<String, Any?>): String {
    val root = JsonSupport.parseObjectOrNull(payload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: error("preplan payload is not a JSON object")
    val produced = JsonSupport.anyToStringAnyMap(root["produced_outputs"])
      ?: error("preplan produced_outputs is not an object")
    return JsonSupport.mapToJsonString(root + ("produced_outputs" to (produced + (SHARED_CONTEXT_FIELD to packet))))
  }

  private fun planningPacketFrom(record: SharedGoalPreplanCheckpoint): Map<String, Any?>? =
    JsonSupport.parseObjectOrNull(record.preplanPayload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("produced_outputs")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(SHARED_CONTEXT_FIELD)
      ?.let(JsonSupport::anyToStringAnyMap)

  private fun validatePlanningPacket(
    packet: Map<String, Any?>,
    repositoryIdentity: String,
    normalizedIssueKey: String,
    parentSpecPath: String,
    parentSpec: String,
    subtasks: List<DecompositionSubtask>,
  ) {
    require(packet.keys == SHARED_CONTEXT_PACKET_FIELDS) { "shared context packet fields are invalid" }
    require(packet["packet_version"] == SHARED_CONTEXT_PACKET_VERSION) { "shared context packet version is invalid" }
    require(packet["repository_identity"] == repositoryIdentity) { "shared context repository identity is invalid" }
    require(packet["normalized_issue_key"] == normalizedIssueKey) { "shared context issue key is invalid" }
    require(packet["parent_spec_path"] == parentSpecPath) { "shared context parent spec path is invalid" }
    require(packet["parent_spec"] == parentSpec.take(MAX_GOVERNED_CONTEXT_CHARS)) {
      "shared context parent spec is invalid"
    }
    require((packet["decomposition_manifest"] as? String)?.length?.let { it <= MAX_GOVERNED_CONTEXT_CHARS } == true) {
      "shared context decomposition manifest is malformed"
    }
    require(isStringMap(packet["platform_packs"])) { "shared context platform packs are invalid" }
    require(isStringMap(packet["boundary_memory"])) { "shared context boundary memory is invalid" }
    require(packet["validation_guidance"] is String) { "shared context validation guidance is invalid" }
    require(packet["ordered_subtasks"] == planningSubtasks(subtasks)) { "shared context ordered subtasks are invalid" }
    require(JsonSupport.mapToJsonString(packet).length <= MAX_SHARED_CONTEXT_PACKET_CHARS) {
      "shared context packet exceeds the size limit"
    }
    require(packet["integrity_sha256"] == packetDigest(packet - "integrity_sha256")) {
      "shared context packet integrity is invalid"
    }
  }

  private fun planningSubtasks(subtasks: List<DecompositionSubtask>): List<Map<String, Any?>> =
    subtasks.map { subtask ->
      linkedMapOf(
        "id" to subtask.id,
        "name" to subtask.name,
        "spec_path" to subtask.specPath,
        "dependencies" to subtask.dependencies.map { dependency ->
          linkedMapOf("subtask_id" to dependency.subtaskId, "optional" to dependency.optional)
        },
      )
    }

  private fun isStringMap(value: Any?): Boolean =
    value is Map<*, *> && value.keys.all { it is String } && value.values.all { it is String }

  private fun packetDigest(packet: Map<String, Any?>): String = sha256HexUtf8(JsonSupport.mapToJsonString(packet))

  private fun immutableDecompositionHash(manifest: skillbill.workflow.model.DecompositionManifest): String {
    val immutable = linkedMapOf<String, Any?>(
      "contract_version" to manifest.contractVersion,
      "issue_key" to manifest.issueKey,
      "feature_name" to manifest.featureName,
      "parent_spec_path" to manifest.parentSpecPath,
      "spec_source" to manifest.specSource.wireValue,
      "execution_model" to manifest.executionModel.wireValue,
      "base_branch" to manifest.baseBranch,
      "feature_branch" to manifest.featureBranch,
      "stack_branches" to manifest.stackBranches.map {
        linkedMapOf("subtask_id" to it.subtaskId, "branch" to it.branch, "base_branch" to it.baseBranch)
      },
      "subtasks" to manifest.subtasks.map { subtask ->
        linkedMapOf(
          "id" to subtask.id,
          "name" to subtask.name,
          "spec_path" to subtask.specPath,
          "linear_issue_id" to subtask.linearIssueId,
          "dependencies" to subtask.dependencies.map { dependency ->
            linkedMapOf(
              "subtask_id" to dependency.subtaskId,
              "optional" to dependency.optional,
              "skipped" to dependency.skipped,
            )
          },
        )
      },
    )
    return sha256HexUtf8(JsonSupport.mapToJsonString(immutable))
  }

  private fun stopped(
    shared: GoalPlanningSharedContext,
    subtaskId: Int,
    blockedReason: String,
    lastResumableStep: String = PHASE_PREPLAN,
  ): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
    issueKey = shared.issueKey,
    currentSubtaskId = subtaskId,
    reason = GoalRunnerStopReason.BLOCKED,
    blockedReason = blockedReason,
    lastResumableStep = lastResumableStep,
  )

  private fun noSuchSubtaskReason(subtaskId: Int): String =
    "Goal planning selected subtask '$subtaskId' which is not present in the accepted decomposition."

  private fun unresolvedSpecReason(subtask: DecompositionSubtask): String =
    "Goal planning subtask '${subtask.id}' governed spec path '${subtask.specPath}' could not be resolved " +
      "inside the repository."

  private fun invariantReadReason(subtask: DecompositionSubtask, error: Throwable): String =
    "Goal planning subtask '${subtask.id}' run-invariants could not be read: ${error.message.orEmpty()}"

  private fun persistenceReason(subtask: DecompositionSubtask, error: Throwable): String =
    "Goal planning subtask '${subtask.id}' prepared pair could not be checkpointed: ${error.message.orEmpty()}"

  private fun resolvedGovernedPath(canonicalRepository: Path, governingPath: String): Path {
    val lexical = lexicalPath(canonicalRepository, governingPath)
    return runCatching { lexical.toRealPath() }.getOrElse { lexical }
  }

  private fun resolvedSubSpecPath(canonicalRepository: Path, specPath: String): Path? {
    if (specPath.isBlank()) return null
    val lexical = lexicalPath(canonicalRepository, specPath)
    val resolved = runCatching { lexical.toRealPath() }.getOrElse { lexical }
    return resolved.takeIf { it.startsWith(canonicalRepository) }
  }

  private fun lexicalPath(canonicalRepository: Path, governingPath: String): Path {
    val path = Path.of(governingPath)
    return (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
  }

  private companion object {
    const val PHASE_PREPLAN: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
    const val PHASE_PLAN: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
    const val SHARED_CONTEXT_FIELD = "_goal_planning_shared_context"
    const val SHARED_CONTEXT_PACKET_VERSION = "0.1"
    const val MAX_GOVERNED_CONTEXT_CHARS = 65_536
    const val MAX_SHARED_CONTEXT_PACKET_CHARS = 524_288
    val SHARED_CONTEXT_PACKET_FIELDS = setOf(
      "packet_version",
      "repository_identity",
      "normalized_issue_key",
      "parent_spec_path",
      "parent_spec",
      "decomposition_manifest",
      "platform_packs",
      "boundary_memory",
      "validation_guidance",
      "ordered_subtasks",
      "integrity_sha256",
    )
  }
}
