package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.boundedSchemaGateDetail
import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.model.GoalPlanningAttemptRecord
import skillbill.application.model.GoalPlanningPhaseProduction
import skillbill.application.model.GoalPlanningSweepOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration

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

@Suppress("LongParameterList", "TooManyFunctions")
@Inject
class DefaultGoalPlanningSweep(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val manifestFileStore: DecompositionManifestFileStore,
  private val contextDiscovery: GoalPlanningContextDiscovery,
  private val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  private val planningAttemptRecorder: GoalPlanningAttemptRecorder = GoalPlanningAttemptRecorder.NONE,
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
      .getOrElse { error -> return preSweepStopped(request, preparationStateReadReason(error)) }
    val recoveredPacket = existingShared?.let(::planningPacketFrom)
    if (existingShared != null && recoveredPacket == null) {
      return preSweepStopped(request, "Goal planning shared preplan does not contain a valid shared context packet.")
    }
    val shared = runCatching { gatherSharedContext(state, request, recoveredPacket) }.getOrElse { error ->
      return preSweepStopped(request, sharedContextReason(error))
    }
    val activeSubtasks = state.manifest.subtasks.filter {
      it.id in GoalPlanningSharedContextPacket.includedSubtaskIds(shared.planningPacket)
    }
    val currentProvenance = currentProvenance(shared)
    val provenance = recoverableProvenance(existingShared, currentProvenance, shared)
      ?: return incompatibleProvenance(shared)
    val sharedCheckpoint = existingShared ?: produceSharedPreplan(shared, request, provenance)
      .getOrElse { error -> return stopped(shared, 0, error.message.orEmpty(), PHASE_PREPLAN) }
    if (activeSubtasks.isEmpty()) return GoalPlanningSweepOutcome.PreparedAll(identity, provenance)
    val descriptors = runCatching { activeSubtasks.mapIndexed { order, subtask -> descriptor(shared, subtask, order) } }
      .getOrElse { error ->
        return stopped(
          shared,
          0,
          "Goal planning governed subtask provenance could not be computed: ${error.message.orEmpty()}",
        )
      }
    val subtasksById = activeSubtasks.associateBy(DecompositionSubtask::id)
    while (true) {
      val recovery = runCatching {
        checkpoint.recoveryProgress(identity, descriptors, provenance, shared.dbPathOverride).firstMissingSubtaskId
      }
      recovery.exceptionOrNull()?.let { error ->
        val subtaskId = recoverySubtaskId(error)
        val phaseId = PHASE_PLAN.takeIf { subtaskId != 0 } ?: PHASE_PREPLAN
        return stopped(shared, subtaskId, preparationStateReadReason(error), phaseId)
      }
      val missingId = recovery.getOrThrow()
      if (missingId == null) return GoalPlanningSweepOutcome.PreparedAll(identity, provenance, descriptors)
      val subtask = subtasksById[missingId]
        ?: return stopped(shared, missingId, noSuchSubtaskReason(missingId))
      val descriptor = descriptors.single { it.subtaskId == missingId }
      producePlan(shared, request, subtask, descriptor, provenance, sharedCheckpoint.preplanPayload)?.let { return it }
    }
  }

  private fun currentProvenance(shared: GoalPlanningSharedContext) = GoalPlanningContractProvenance(
    shared.parentSpecHash,
    shared.decompositionManifestHash,
    GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
  )

  private fun recoverableProvenance(
    existing: SharedGoalPreplanCheckpoint?,
    current: GoalPlanningContractProvenance,
    shared: GoalPlanningSharedContext,
  ): GoalPlanningContractProvenance? {
    val existingProvenance = existing?.provenance ?: return current
    if (existingProvenance == current) return current
    val savedParentSpec = shared.planningPacket["parent_spec"] as? String
    return existingProvenance.takeIf {
      it.decompositionManifestHash == current.decompositionManifestHash &&
        it.phaseOutputContractId == current.phaseOutputContractId &&
        savedParentSpec != null &&
        sha256HexUtf8(savedParentSpec) == it.parentSpecHash &&
        GoalPlanningSpecCanonicalization.canonical(savedParentSpec) ==
        GoalPlanningSpecCanonicalization.canonical(shared.parentSpec)
    }
  }

  private fun incompatibleProvenance(shared: GoalPlanningSharedContext): GoalPlanningSweepOutcome.Stopped = stopped(
    shared,
    0,
    "Goal planning preparation cannot be recovered because the current governed parent spec or immutable " +
      "decomposition provenance differs from the saved shared preplan.",
    PHASE_PREPLAN,
  )

  private fun recoverySubtaskId(error: Throwable): Int {
    val recoveryError = error as? IncompatibleGoalPlanningPreparationRecoveryError
    if (
      recoveryError != null &&
      error.message?.contains("must be completed with non-empty produced_outputs") == true
    ) {
      return 0
    }
    return recoveryError?.subtaskId ?: 0
  }

  @Suppress("ReturnCount")
  private fun produceSharedPreplan(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    provenance: GoalPlanningContractProvenance,
  ): Result<SharedGoalPreplanCheckpoint> = runCatching {
    val runInvariants = invariantsSource.read(shared.parentSpecPath)
    // The bytes actually checkpointed are the enriched ones, so enrichment is the finalizer the
    // producer gate runs on. Gating the raw child stdout would let an enrichment that invalidates the
    // projection settle unchecked.
    val preplanProduction = producePhase(shared, request, null, runInvariants, PHASE_PREPLAN, emptyList()) { raw ->
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
    ).also { checkpoint.recheckpointSharedPreplan(it, shared.dbPathOverride) }
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
      ?: return stopped(shared, subtask.id, unresolvedSpecReason(subtask), PHASE_PLAN)
    val runInvariants = runCatching { invariantsSource.read(resolvedSpecPath) }.getOrElse { error ->
      return stopped(shared, subtask.id, invariantReadReason(subtask, error), PHASE_PLAN)
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
    val captured = planProduction as GoalPlanningPhaseProduction.Captured
    val planPayload = captured.payload
    val record = GoalSubtaskPlanCheckpoint(
      identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
      subtaskId = subtask.id,
      manifestOrder = descriptor.manifestOrder,
      governedSubSpecPath = descriptor.governedSubSpecPath,
      subSpecHash = descriptor.subSpecHash,
      provenance = provenance,
      payloadSha256 = sha256HexUtf8(planPayload),
      planPayload = planPayload,
      repairEvidence = captured.repairEvidence,
    )
    return runCatching { checkpoint.recheckpointSubtaskPlan(record, shared.dbPathOverride) }.fold(
      onSuccess = { null },
      onFailure = { error -> stopped(shared, subtask.id, persistenceReason(subtask, error), PHASE_PLAN) },
    )
  }

  private fun descriptor(
    shared: GoalPlanningSharedContext,
    subtask: DecompositionSubtask,
    order: Int,
  ): GovernedGoalSubtaskDescriptor {
    val path = resolvedSubSpecPath(shared.repoRoot, subtask.specPath) ?: error(unresolvedSpecReason(subtask))
    val governedPath = shared.repoRoot.relativize(path).joinToString("/")
    val identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity)
    // Reads the stored record directly: a governed sub-spec that no longer exists on disk can only recover its
    // hash from what was persisted, and that recovery must not depend on the stored plan's projection verdict.
    val recovered = checkpoint.findStoredSubtaskPlan(
      identity,
      subtask.id,
      governedPath,
      shared.dbPathOverride,
    )
    val subSpecHash = if (manifestFileStore.isRegularFile(path)) {
      sha256HexUtf8(manifestFileStore.readText(path))
    } else {
      require(recovered != null && shared.specSource == SpecSource.LINEAR && subtask.status == "complete") {
        unresolvedSpecReason(subtask)
      }
      recovered.subSpecHash
    }
    return GovernedGoalSubtaskDescriptor(
      subtask.id,
      order,
      governedPath,
      subSpecHash,
    )
  }

  /**
   * Produces one planning phase and gates the exact payload bytes that will be checkpointed through the
   * shared producer projection gate. A projection-invalid output relaunches the same phase with the
   * bounded validation detail in the remediation prompt, under the one runtime fix-loop cap; nothing is
   * checkpointed in the failing state, and exhaustion stops the sweep with that detail as the reason.
   *
   * Fix-loop budget limitation: the consumed budget is tracked in-memory only and resets on each
   * resume. A phase that exhausts MAX_FIX_LOOP_ITERATIONS and stops durably will restart at
   * attempt 1 on the next resume rather than remaining stopped. Operators should monitor for
   * repeated fix-loop exhaustion and intervene manually.
   *
   * Aggregate launch ceiling: there is no global cap on total planning-agent launches across all
   * phases. Each call to producePhase (preplan + one per included subtask) independently attempts
   * up to MAX_FIX_LOOP_ITERATIONS launches. A goal with N subtasks can issue up to (N+1) *
   * MAX_FIX_LOOP_ITERATIONS planning launches. The --planning-budget-minutes and
   * --max-wall-clock-minutes flags bound per-launch timeout and subtask launches respectively,
   * not the sweep's total planning budget. Operators should monitor for excessive planning
   * launch counts and consider adjusting MAX_FIX_LOOP_ITERATIONS or the spec size.

   * Every attempt records a durable completion event on the parent workflow, including its phase,
   * subtask, ordinal, and success/failure outcome. Resume therefore preserves the consumed attempt
   * evidence even though the bounded retry counter remains scoped to one sweep run.
   */
  private fun producePhase(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask?,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    phaseId: String,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    finalizePayload: (String) -> String = { it },
  ): GoalPlanningPhaseProduction {
    var priorSchemaFailure: String? = null
    repeat(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS) { attemptIndex ->
      val attempt = attemptIndex + 1
      val production = produceAttempt(
        shared,
        request,
        subtask,
        runInvariants,
        phaseId,
        recordedOutputs,
        priorSchemaFailure,
      )
      if (production is GoalPlanningPhaseProduction.Stopped) {
        recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
        return production
      }
      val captured = production as GoalPlanningPhaseProduction.Captured
      val payload = finalizePayload(captured.payload)
      val accepted = if (payload == captured.payload) {
        skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput(
          normalizedOutput = captured.normalizedOutput,
          repairEvidence = captured.repairEvidence,
        )
      } else {
        outputValidator.validatePhaseOutput(payload, phaseId).requireAcceptedOutput(phaseId)
      }
      val canonicalPayload = accepted.normalizedOutput.canonicalJson
      val gateReason = projectionGateReason(canonicalPayload, phaseId)
      if (gateReason == null) {
        recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.SUCCEEDED)
        return GoalPlanningPhaseProduction.Captured(
          canonicalPayload,
          accepted.normalizedOutput,
          // Enrichment revalidates the final payload, but it must not discard evidence captured
          // while structurally repairing the child output before enrichment.
          accepted.repairEvidence ?: captured.repairEvidence,
        )
      }
      recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
      priorSchemaFailure = gateReason
    }
    return GoalPlanningPhaseProduction.Stopped(
      stopped(shared, subtask?.id ?: 0, fixLoopExhaustedReason(phaseId, priorSchemaFailure.orEmpty()), phaseId),
    )
  }

  private fun recordPlanningAttempt(
    shared: GoalPlanningSharedContext,
    phaseId: String,
    subtask: DecompositionSubtask?,
    attempt: Int,
    outcome: GoalProgressOutcome,
  ) {
    planningAttemptRecorder.record(
      GoalPlanningAttemptRecord(
        shared.parentWorkflowId,
        shared.issueKey,
        shared.dbPathOverride,
        phaseId,
        subtask?.id ?: 0,
        attempt,
        outcome,
      ),
    )
  }

  private fun projectionGateReason(payload: String, phaseId: String): String? {
    val envelope = JsonSupport.parseObjectOrNull(payload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return "Goal planning '$phaseId' payload is not a JSON object."
    return producerProjectionGateReason(phaseId, envelope, planningProjectionValidator)
      ?.let(::boundedSchemaGateDetail)
  }

  private fun fixLoopExhaustedReason(phaseId: String, lastFailure: String): String =
    "Goal planning '$phaseId' produced a projection-invalid output on every attempt " +
      "(cap=${FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS}); nothing was checkpointed. " +
      "Last projection failure: $lastFailure"

  private fun produceAttempt(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask?,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    phaseId: String,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    priorSchemaFailure: String?,
  ): GoalPlanningPhaseProduction {
    val currentSubtaskId = subtask?.id ?: 0
    // A recovered shared preplan is already settled by the time its bounded projection is parsed here,
    // so an unhandled rejection would crash the goal driver with no Stopped outcome, no blocked_reason
    // and no closed telemetry segment, then crash identically on every resume. Block durably instead.
    val prompt = runCatching {
      composePlanningPrompt(shared, request, subtask, runInvariants, phaseId, recordedOutputs, priorSchemaFailure)
    }
      .getOrElse { error ->
        if (error !is InvalidFeatureTaskRuntimePlanningProjectionSchemaError &&
          error !is InvalidFeatureTaskRuntimeHandoffProjectionError
        ) {
          throw error
        }
        return GoalPlanningPhaseProduction.Stopped(
          stopped(shared, currentSubtaskId, projectionRejectedReason(phaseId, error), phaseId),
        )
      }
    request.outputSink.write(AgentRunOutputStream.STDERR, planningProgressMessage(phaseId, subtask))
    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = shared.invokedAgentId,
        configuredAgentOverrideId = shared.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = request.issueKey,
          repoRoot = shared.repoRoot,
          subtaskId = subtask?.id,
          dbPathOverride = shared.dbPathOverride,
          // Planning checkpoints only after the child exits, so it emits no durable progress
          // token and no worktree activity. It proves liveness by streaming output instead:
          // the idle window then bounds silence, and the budget bounds total time.
          timeout = request.planningBudget,
          progressIdleTimeout = request.progressIdleTimeout,
          outputSink = request.outputSink,
          promptOverride = prompt,
          streamOutputForLiveness = true,
        ),
      ),
    )
    val stdout = stdoutFor(outcome)
      ?: return GoalPlanningPhaseProduction.Stopped(
        stopped(shared, currentSubtaskId, exhaustedReason(outcome, request.planningBudget), phaseId),
      )
    return runCatching {
      outputValidator.validatePhaseOutput(stdout, phaseId).requireAcceptedOutput(phaseId)
    }.fold(
      onSuccess = { accepted ->
        val payload = accepted.normalizedOutput.envelope
        if (payload["status"] != "completed") {
          GoalPlanningPhaseProduction.Stopped(
            stopped(shared, currentSubtaskId, unsuccessfulStatusReason(phaseId, payload["status"]), phaseId),
          )
        } else {
          GoalPlanningPhaseProduction.Captured(
            accepted.normalizedOutput.canonicalJson,
            accepted.normalizedOutput,
            accepted.repairEvidence,
          )
        }
      },
      onFailure = { error ->
        GoalPlanningPhaseProduction.Stopped(stopped(shared, currentSubtaskId, malformedReason(phaseId, error), phaseId))
      },
    )
  }

  private fun composePlanningPrompt(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask?,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    phaseId: String,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    priorSchemaFailure: String?,
  ): String {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, runInvariants.featureSize),
      runInvariants = runInvariants,
      recordedOutputs = recordedOutputs,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      planningProjectionValidator = planningProjectionValidator,
      agentAddonSelection = request.agentAddonSelection,
    )
    val basePrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      issueKey = request.issueKey,
      briefing = briefing,
      suppressDecomposition = true,
      specSource = shared.specSource,
      specReference = runInvariants.specReference,
      priorSchemaFailure = priorSchemaFailure,
    )
    return GoalPlanningContextPromptFormatter.append(basePrompt, shared.planningPacket, subtask, phaseId)
  }

  private fun gatherSharedContext(
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
    val planningPacket = recoveredPacket ?: contextDiscovery.discover(canonicalRepository).let { discovered ->
      val packet = linkedMapOf<String, Any?>(
        "packet_version" to GoalPlanningSharedContextPacket.VERSION,
        "repository_identity" to repositoryIdentity,
        "normalized_issue_key" to state.manifest.issueKey.trim().uppercase(),
        "parent_spec_path" to parentSpecGoverningPath,
        "parent_spec" to parentSpec.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        "decomposition_manifest" to decomposition.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        "platform_packs" to discovered.platformPacks,
        "boundary_memory" to discovered.boundaryMemory,
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

  private fun canonicalRepository(repoRoot: Path): Path = runCatching { repoRoot.toRealPath() }
    .getOrElse { repoRoot.toAbsolutePath().normalize() }

  private fun planningProgressMessage(phaseId: String, subtask: DecompositionSubtask?): String =
    if (phaseId == PHASE_PREPLAN) {
      "skill-bill: goal planning - parent goal shared preplan\n"
    } else {
      "skill-bill: goal planning - subtask ${requireNotNull(subtask).id} plan\n"
    }

  private fun sharedContextReason(error: Throwable): String =
    "Goal planning shared context could not be gathered: ${error.message.orEmpty()}"

  private fun projectionRejectedReason(phaseId: String, error: Throwable): String =
    "Goal planning phase '$phaseId' rejected a declared bounded projection at the launch seam: " +
      "${error.message.orEmpty()}. Migrate or delete the affected goal-planning preparation record."

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

  private fun exhaustedReason(outcome: AgentRunLaunchOutcome, planningBudget: Duration?): String = when (outcome) {
    is UnsupportedAgentRunLaunch -> "Goal planning could not launch a planning agent: ${outcome.reason}"
    is AgentRunLaunchFacts ->
      "Goal planning produced no usable agent output: ${exhaustedCause(outcome, planningBudget)}."
  }

  private fun exhaustedCause(facts: AgentRunLaunchFacts, planningBudget: Duration?): String = when {
    facts.spawnFailed -> "the planning agent failed to spawn"
    facts.timedOut ->
      "the planning agent exhausted its $planningBudget planning budget; " +
        "raise or disable it with --planning-budget-minutes"
    facts.interrupted -> "the planning agent was interrupted"
    facts.exitStatus != null && facts.exitStatus != 0 -> "the planning agent exited with status ${facts.exitStatus}"
    else -> "the planning agent produced no usable output"
  }

  private fun malformedReason(phaseId: String, error: Throwable): String =
    "Goal planning '$phaseId' output failed the schema gate and could not be prepared: ${error.message.orEmpty()}"

  private fun unsuccessfulStatusReason(phaseId: String, status: Any?): String =
    "Goal planning '$phaseId' stopped with status '${status ?: "missing"}'; its output was not checkpointed."

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
    "Goal planning subtask '${subtask.id}' plan could not be checkpointed: ${error.message.orEmpty()}"

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
  }
}

fun interface GoalPlanningAttemptRecorder {
  fun record(attempt: GoalPlanningAttemptRecord)

  companion object {
    val NONE: GoalPlanningAttemptRecorder = GoalPlanningAttemptRecorder {}
  }
}

@Inject
class DurableGoalPlanningAttemptRecorder(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) : GoalPlanningAttemptRecorder {
  private val nextSequenceByWorkflow = mutableMapOf<String, Int>()

  @Synchronized
  override fun record(attempt: GoalPlanningAttemptRecord) {
    outcomeStore.recordProgressEvent(
      GoalRunnerProgressEventRecordRequest(
        workflowId = attempt.parentWorkflowId,
        event = GoalProgressEvent(
          eventKind = GoalProgressEventKind.OPERATION_COMPLETED,
          workflowId = attempt.parentWorkflowId,
          workflowPhase = "goal_planning",
          processAlive = true,
          sequenceNumber = nextSequenceByWorkflow.getOrPut(attempt.parentWorkflowId) {
            outcomeStore.ledgerSequenceWatermarks(attempt.issueKey, attempt.dbPathOverride)
              .maxProgressSequence
              ?.plus(1)
              ?: 0
          },
          timestamp = Instant.now().toString(),
          stepId = attempt.phaseId,
          operationName = "${attempt.phaseId}:${attempt.subtaskId}:attempt:${attempt.attempt}",
          operationKind = "planning_projection_attempt",
          expectedLong = true,
          outcome = attempt.outcome,
        ),
      ),
      attempt.dbPathOverride,
    )
    nextSequenceByWorkflow[attempt.parentWorkflowId] = nextSequenceByWorkflow.getValue(attempt.parentWorkflowId) + 1
  }
}
