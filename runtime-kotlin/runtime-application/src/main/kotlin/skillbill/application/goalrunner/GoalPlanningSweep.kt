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
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
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
  private val database: DatabaseSessionFactory,
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val manifestFileStore: DecompositionManifestFileStore,
  private val contextDiscovery: GoalPlanningContextDiscovery,
) : GoalPlanningSweep {
  @Suppress("ReturnCount")
  override fun prepare(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalPlanningSweepOutcome {
    val prepared = runCatching {
      database.read(request.dbPathOverride) { unitOfWork ->
        unitOfWork.goalPlanningPreparations.listPreparedByGoalOrdered(state.parentWorkflowId)
      }
    }.getOrElse { error -> return preSweepStopped(request, preparationStateReadReason(error)) }
    prepared.firstOrNull { record -> runCatching { checkpoint.validate(record) }.isFailure }?.let { record ->
      return preSweepStopped(
        request,
        "Goal planning subtask '${record.subtaskId}' has an invalid saved prepared pair; resume is refused.",
        record.subtaskId,
      )
    }
    val recoveredPacket = prepared.firstOrNull()?.let(::planningPacketFrom)
    if (prepared.isNotEmpty() && recoveredPacket == null) {
      return preSweepStopped(request, "Goal planning prepared rows do not contain a valid shared context packet.")
    }
    val shared = runCatching { gatherSharedContext(state, request, recoveredPacket) }.getOrElse { error ->
      return preSweepStopped(request, sharedContextReason(error))
    }
    val subtasksById = state.manifest.subtasks.associateBy(DecompositionSubtask::id)
    val orderedNonSkippedIds = state.manifest.subtasks
      .filter { it.status != "skipped" }
      .map(DecompositionSubtask::id)
    incompatibleProvenanceStop(shared, state)?.let { return it }
    while (true) {
      val missingId = runCatching {
        database.read(shared.dbPathOverride) { unitOfWork ->
          unitOfWork.goalPlanningPreparations
            .firstMissingOrIncompleteSubtask(shared.parentWorkflowId, orderedNonSkippedIds)
        }
      }.getOrElse { error -> return stopped(shared, 0, preparationStateReadReason(error)) }
      if (missingId == null) return GoalPlanningSweepOutcome.PreparedAll
      val subtask = subtasksById[missingId]
        ?: return stopped(shared, missingId, noSuchSubtaskReason(missingId))
      producePair(shared, request, subtask)?.let { return it }
    }
  }

  @Suppress("ReturnCount")
  private fun producePair(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask,
  ): GoalPlanningSweepOutcome.Stopped? {
    val resolvedSpecPath = resolvedSubSpecPath(shared.repoRoot, subtask.specPath)
      ?: return stopped(shared, subtask.id, unresolvedSpecReason(subtask))
    val runInvariants = runCatching { invariantsSource.read(resolvedSpecPath) }.getOrElse { error ->
      return stopped(shared, subtask.id, invariantReadReason(subtask, error))
    }
    val preplanProduction = producePhase(shared, request, subtask, runInvariants, PHASE_PREPLAN, emptyList())
    if (preplanProduction is GoalPlanningPhaseProduction.Stopped) return preplanProduction.outcome
    val rawPreplanPayload = (preplanProduction as GoalPlanningPhaseProduction.Captured).payload
    val preplanPayload = runCatching { enrichPreplan(rawPreplanPayload, shared.planningPacket) }.getOrElse { error ->
      return stopped(shared, subtask.id, malformedReason(PHASE_PREPLAN, error), PHASE_PREPLAN)
    }
    runCatching { outputValidator.validatePhaseOutputText(preplanPayload, PHASE_PREPLAN) }.getOrElse { error ->
      return stopped(shared, subtask.id, malformedReason(PHASE_PREPLAN, error), PHASE_PREPLAN)
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
    val subSpecHash = runCatching { sha256HexUtf8(manifestFileStore.readText(resolvedSpecPath)) }.getOrElse { error ->
      return stopped(shared, subtask.id, subSpecHashReason(subtask, error))
    }
    val record = GoalPlanningPreparationRecord(
      parentGoalWorkflowId = shared.parentWorkflowId,
      normalizedIssueKey = shared.normalizedIssueKey,
      repositoryIdentity = shared.repositoryIdentity,
      subtaskId = subtask.id,
      governedSubSpecPath = shared.repoRoot.relativize(resolvedSpecPath).joinToString("/"),
      preparationStatus = GoalPlanningPreparationState.PREPARED,
      provenance = GoalPlanningPreparationProvenance(
        parentSpecHash = shared.parentSpecHash,
        subSpecHash = subSpecHash,
        decompositionManifestHash = shared.decompositionManifestHash,
      ),
      preplanPayload = preplanPayload,
      planPayload = planPayload,
    )
    runCatching { checkpoint.checkpoint(record, shared.dbPathOverride) }.getOrElse { error ->
      return stopped(shared, subtask.id, persistenceReason(subtask, error))
    }
    return null
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
      ?: return GoalPlanningPhaseProduction.Stopped(stopped(shared, subtask.id, exhaustedReason(outcome), phaseId))
    return runCatching { outputValidator.validateAndReadPhaseOutput(stdout, phaseId) }.fold(
      onSuccess = { payload ->
        if (payload["status"] != "completed") {
          GoalPlanningPhaseProduction.Stopped(
            stopped(shared, subtask.id, unsuccessfulStatusReason(phaseId, payload["status"]), phaseId),
          )
        } else {
          GoalPlanningPhaseProduction.Captured(JsonSupport.mapToJsonString(payload))
        }
      },
      onFailure = { error ->
        GoalPlanningPhaseProduction.Stopped(stopped(shared, subtask.id, malformedReason(phaseId, error), phaseId))
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
        "ordered_subtasks" to state.manifest.subtasks.map { subtask ->
          linkedMapOf(
            "id" to subtask.id,
            "name" to subtask.name,
            "spec_path" to subtask.specPath,
            "dependencies" to subtask.dependencies.map { dependency ->
              linkedMapOf("subtask_id" to dependency.subtaskId, "optional" to dependency.optional)
            },
          )
        },
      )
      packet + ("integrity_sha256" to packetDigest(packet))
    }
    require(planningPacket["packet_version"] == SHARED_CONTEXT_PACKET_VERSION)
    require(planningPacket["repository_identity"] == repositoryIdentity)
    require(planningPacket["normalized_issue_key"] == state.manifest.issueKey.trim().uppercase())
    require(JsonSupport.mapToJsonString(planningPacket).length <= MAX_SHARED_CONTEXT_PACKET_CHARS)
    require(planningPacket["integrity_sha256"] == packetDigest(planningPacket - "integrity_sha256"))
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

  private fun incompatibleProvenanceStop(
    shared: GoalPlanningSharedContext,
    state: GoalRunnerManifestState,
  ): GoalPlanningSweepOutcome.Stopped? {
    val prepared = runCatching {
      database.read(shared.dbPathOverride) { unitOfWork ->
        unitOfWork.goalPlanningPreparations.listPreparedByGoalOrdered(shared.parentWorkflowId)
      }
    }.getOrElse { error -> return stopped(shared, 0, preparationStateReadReason(error)) }
    return prepared.firstOrNull { record ->
      val expectedPath = state.manifest.subtasks.singleOrNull { it.id == record.subtaskId }
        ?.specPath
        ?.let { path -> resolvedSubSpecPath(shared.repoRoot, path) }
        ?.let { path -> shared.repoRoot.relativize(path).joinToString("/") }
      val subtask = record.governedSubSpecPath.takeIf(String::isNotBlank)?.let { path ->
        resolvedSubSpecPath(shared.repoRoot, path)
      }
      val subSpecHashMismatch = subtask
        ?.takeIf(manifestFileStore::isRegularFile)
        ?.let { path ->
          val currentHash = runCatching { sha256HexUtf8(manifestFileStore.readText(path)) }.getOrNull()
          currentHash == null || record.provenance.subSpecHash != currentHash
        }
        ?: false
      record.normalizedIssueKey != shared.normalizedIssueKey ||
        record.repositoryIdentity != shared.repositoryIdentity ||
        record.governedSubSpecPath != expectedPath ||
        record.contractVersion != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ||
        record.provenance.parentSpecHash != shared.parentSpecHash ||
        record.provenance.decompositionManifestHash != shared.decompositionManifestHash ||
        subSpecHashMismatch ||
        record.provenance.phaseOutputContractId != FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID ||
        record.provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION
    }?.let { record -> stopped(shared, record.subtaskId, incompatibleProvenanceReason(record)) }
  }

  private fun preSweepStopped(
    request: GoalRunnerRunRequest,
    reason: String,
    currentSubtaskId: Int = 0,
  ): GoalPlanningSweepOutcome.Stopped =
    GoalPlanningSweepOutcome.Stopped(
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

  private fun incompatibleProvenanceReason(record: GoalPlanningPreparationRecord): String =
    "Goal planning subtask '${record.subtaskId}' prepared provenance is incompatible with the current parent " +
      "spec or decomposition manifest; the stale plan is rejected. Re-decompose or clear the stale " +
      "preparation before resuming."

  private fun stdoutFor(outcome: AgentRunLaunchOutcome): String? = when (outcome) {
    is AgentRunLaunchFacts -> outcome.stdout.takeIf { stdout ->
      !outcome.spawnFailed && !outcome.timedOut && !outcome.interrupted && outcome.exitStatus == 0 && stdout.isNotBlank()
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

  private fun planningPacketFrom(record: GoalPlanningPreparationRecord): Map<String, Any?>? =
    JsonSupport.parseObjectOrNull(record.preplanPayload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("produced_outputs")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(SHARED_CONTEXT_FIELD)
      ?.let(JsonSupport::anyToStringAnyMap)

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

  private fun subSpecHashReason(subtask: DecompositionSubtask, error: Throwable): String =
    "Goal planning subtask '${subtask.id}' provenance could not be computed: ${error.message.orEmpty()}"

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
  }
}
