package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.boundedSchemaGateDetail
import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.model.GoalPlanningAttemptRecord
import skillbill.application.model.GoalPlanningBurstSchedule
import skillbill.application.model.GoalPlanningEmptyTurnEvidence
import skillbill.application.model.GoalPlanningPhaseProduction
import skillbill.application.model.GoalPlanningRejectionRecord
import skillbill.application.model.GoalPlanningSweepOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.model.DecompositionManifest
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
import kotlin.time.Duration.Companion.ZERO

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

@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
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
  private val manifestStore: GoalRunnerManifestStore,
  private val planningRejectionRecorder: GoalPlanningRejectionRecorder = GoalPlanningRejectionRecorder.NONE,
  private val timingPort: RuntimeTimingPort = NoopRuntimeTimingPort,
  private val burstSchedule: GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(),
  private val boundaryBodyResolver: GoalPlanningBoundaryBodyResolver,
  private val refreshLiveness: GoalPlanningRefreshLiveness = GoalPlanningRefreshLiveness.IDLE,
) : GoalPlanningSweep {
  @Suppress("ReturnCount", "LongMethod")
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
      return preSweepStopped(request, "Goal planning shared preplan does not contain a valid shared context packet.")
    }
    var shared = runCatching { gatherSharedContext(state, request, recoveredPacket) }.getOrElse { error ->
      return preSweepStopped(request, sharedContextReason(error))
    }
    val activeSubtasks = state.manifest.subtasks.filter {
      it.id in GoalPlanningSharedContextPacket.includedSubtaskIds(shared.planningPacket)
    }
    val currentProvenance = currentProvenance(shared)
    // At most one in-run shared-preplan regeneration per prepare()/launch.
    var refreshedThisPrepare = false
    val (provenance, sharedCheckpoint) = when (
      val recoverability = classifyRecoverability(existingShared, currentProvenance, shared)
    ) {
      is GoalPlanningProvenanceRecoverability.Invalid -> return incompatibleProvenance(shared)
      is GoalPlanningProvenanceRecoverability.Reuse -> {
        val settled = existingShared ?: produceSharedPreplan(shared, request, recoverability.provenance)
          .getOrElse { error -> return stopped(shared, 0, error.message.orEmpty(), PHASE_PREPLAN) }
        recoverability.provenance to settled
      }
      is GoalPlanningProvenanceRecoverability.StaleValid -> {
        val first = refreshStaleSharedPreplan(
          existing = requireNotNull(existingShared),
          shared = shared,
          state = state,
          request = request,
          currentProvenance = currentProvenance,
          refreshedThisPrepare = refreshedThisPrepare,
        ).getOrElse { error ->
          return when (error) {
            is RefreshRefused -> stopped(shared, 0, error.reason, PHASE_PREPLAN)
            else -> stopped(shared, 0, error.message.orEmpty(), PHASE_PREPLAN)
          }
        }
        refreshedThisPrepare = true
        val afterRefresh = runCatching {
          checkpoint.findSharedPreplan(identity, request.dbPathOverride)
        }.getOrElse { error ->
          return preSweepStopped(
            request,
            preparationStateReadReason(error, request.issueKey, 0),
          )
        }
          ?: first.checkpoint
        val afterPacket = planningPacketFrom(afterRefresh) ?: shared.planningPacket
        // Continue prepare with the post-refresh packet so cascaded plan regen uses the same
        // parent_spec/catalog the new preplan selected against, not the pre-refresh recovered packet.
        shared = shared.copy(planningPacket = afterPacket)
        when (val second = classifyRecoverability(afterRefresh, currentProvenance, shared)) {
          is GoalPlanningProvenanceRecoverability.Invalid -> return incompatibleProvenance(shared)
          is GoalPlanningProvenanceRecoverability.Reuse -> second.provenance to afterRefresh
          is GoalPlanningProvenanceRecoverability.StaleValid -> {
            // Latch: a second stale classification in this prepare must not re-enter refresh.
            refreshStaleSharedPreplan(
              existing = afterRefresh,
              shared = shared,
              state = state,
              request = request,
              currentProvenance = currentProvenance,
              refreshedThisPrepare = refreshedThisPrepare,
            ).getOrElse { error ->
              return when (error) {
                is RefreshRefused -> stopped(shared, 0, error.reason, PHASE_PREPLAN)
                else -> stopped(shared, 0, error.message.orEmpty(), PHASE_PREPLAN)
              }
            }.let { it.provenance to it.checkpoint }
          }
        }
      }
    }
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
    // The preplan payload is immutable for this prepare(), so every subtask's plan prompt resolves
    // the same bodies. Resolving inside producePlan re-read and re-parsed every referenced boundary
    // file once per subtask for a provably identical result.
    val resolvedBodies = resolvedBoundaryBodies(shared, sharedCheckpoint.preplanPayload)
    // Pace only between consecutive plan launches in this prepare() call — never before the first
    // (including the first plan after resume) and never after the last.
    var plansLaunchedThisPrepare = 0
    while (true) {
      val recovery = runCatching {
        checkpoint.recoveryProgress(identity, descriptors, provenance, shared.dbPathOverride).firstMissingSubtaskId
      }
      recovery.exceptionOrNull()?.let { error ->
        val subtaskId = recoverySubtaskId(error)
        val phaseId = PHASE_PLAN.takeIf { subtaskId != 0 } ?: PHASE_PREPLAN
        return stopped(
          shared,
          subtaskId,
          preparationStateReadReason(error, shared.issueKey, subtaskId),
          phaseId,
        )
      }
      val missingId = recovery.getOrThrow()
      if (missingId == null) return GoalPlanningSweepOutcome.PreparedAll(identity, provenance, descriptors)
      val subtask = subtasksById[missingId]
        ?: return stopped(shared, missingId, noSuchSubtaskReason(missingId))
      val descriptor = descriptors.single { it.subtaskId == missingId }
      if (plansLaunchedThisPrepare > 0) {
        interruptibleWait(burstSchedule.planLaunchPace, shared, missingId, PHASE_PLAN)?.let { return it }
      }
      producePlan(shared, request, subtask, descriptor, provenance, sharedCheckpoint.preplanPayload, resolvedBodies)
        ?.let { return it }
      plansLaunchedThisPrepare += 1
    }
  }

  private fun currentProvenance(shared: GoalPlanningSharedContext) = GoalPlanningContractProvenance(
    shared.parentSpecHash,
    shared.decompositionManifestHash,
    GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
  )

  /**
   * Classifies saved shared-preplan recoverability. Payload integrity and selected-heading resolution
   * always run when a checkpoint exists — never short-circuit on provenance equality alone.
   * Fresh catalog ids come from [contextDiscovery], not the recovered packet catalog.
   *
   * When provenance was already advanced to the current parent-spec hash (equal-set refresh) while the
   * embedded packet still carries the prior parent_spec text, treat the current on-disk parent spec as
   * the saved text for classification so self-hash and freshness stay coherent without rewriting payload
   * bytes.
   */
  private fun classifyRecoverability(
    existing: SharedGoalPreplanCheckpoint?,
    current: GoalPlanningContractProvenance,
    shared: GoalPlanningSharedContext,
  ): GoalPlanningProvenanceRecoverability {
    if (existing == null) {
      return GoalPlanningProvenanceRecoverability.Reuse(current)
    }
    val selected = selectedBoundaryHeadingIds(existing.preplanPayload)
    val freshCatalogHeadingIds = if (selected.isEmpty()) {
      emptySet()
    } else {
      contextDiscovery.discover(shared.repoRoot).boundaryCatalog.mapTo(linkedSetOf()) { it.headingId }
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
      freshCatalogHeadingIds = freshCatalogHeadingIds,
    )
  }

  private data class RefreshedSharedPreplan(
    val provenance: GoalPlanningContractProvenance,
    val checkpoint: SharedGoalPreplanCheckpoint,
  )

  private class RefreshRefused(val reason: String) : RuntimeException(reason)

  /**
   * In-run refresh for a valid-but-stale shared preplan. One regeneration per prepare(); heading-set
   * equality decides provenance-only advance vs full payload replace + shared cascade helper.
   */
  @Suppress("ReturnCount")
  private fun refreshStaleSharedPreplan(
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
    val savedHeadings = selectedBoundaryHeadingIds(existing.preplanPayload).toSet()
    val newHeadings = selectedBoundaryHeadingIds(produced.preplanPayload).toSet()
    if (savedHeadings == newHeadings) {
      checkpoint.advanceSharedPreplanProvenance(
        identity = existing.identity,
        expectedPayloadSha256 = existing.payloadSha256,
        provenance = currentProvenance,
        dbOverride = shared.dbPathOverride,
      )
      val advanced = existing.copy(provenance = currentProvenance)
      RefreshedSharedPreplan(currentProvenance, advanced)
    } else {
      val cascadeIds = cascadeEligiblePlanSubtaskIds(
        plannedIds = checkpoint.listPreparedPlanSubtaskIds(state.parentWorkflowId, shared.dbPathOverride),
        subtasks = state.manifest.subtasks,
      )
      val replaced = checkpoint.replaceSharedPreplanForRefresh(
        checkpoint = produced,
        expectedPayloadSha256 = existing.payloadSha256,
        cascadePlanSubtaskIds = cascadeIds,
        dbOverride = shared.dbPathOverride,
      )
      RefreshedSharedPreplan(currentProvenance, replaced)
    }
  }

  private fun freshPlanningPacket(
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

  private fun incompatibleProvenance(shared: GoalPlanningSharedContext): GoalPlanningSweepOutcome.Stopped {
    val remedySubtaskId = shared.manifest.subtasks.firstOrNull { it.status != "skipped" }?.id ?: 1
    return stopped(
      shared,
      0,
      goalPlanningIncompatibleProvenanceStopReason(shared.issueKey, remedySubtaskId),
      PHASE_PREPLAN,
    )
  }

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
  ): Result<SharedGoalPreplanCheckpoint> =
    produceSharedPreplanCheckpoint(shared, request, provenance).mapCatching { produced ->
      produced.also { checkpoint.recheckpointSharedPreplan(it, shared.dbPathOverride) }
    }

  private fun produceSharedPreplanCheckpoint(
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
    )
  }

  private fun producePlan(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask,
    descriptor: GovernedGoalSubtaskDescriptor,
    provenance: GoalPlanningContractProvenance,
    preplanPayload: String,
    resolvedBodies: GoalPlanningResolvedBoundaryBodies,
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
      resolvedBodies = resolvedBodies,
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
    // A complete subtask's plan is never hydrated into a fresh child again, so its stored hash stays
    // authoritative: a sub-spec edited or deleted after completion must not wedge goal recovery.
    val subSpecHash = when {
      recovered != null && subtask.status == "complete" -> recovered.subSpecHash
      manifestFileStore.isRegularFile(path) -> sha256HexUtf8(manifestFileStore.readText(path))
      else -> error(unresolvedSpecReason(subtask))
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
    resolvedBodies: GoalPlanningResolvedBoundaryBodies = GoalPlanningResolvedBoundaryBodies(),
    finalizePayload: (String) -> String = { it },
  ): GoalPlanningPhaseProduction {
    var priorSchemaFailure: String? = null
    var lastRejection: String? = null
    repeat(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS) { attemptIndex ->
      val attempt = attemptIndex + 1
      val production = produceAttemptOrStop(
        shared,
        request,
        subtask,
        runInvariants,
        phaseId,
        recordedOutputs,
        priorSchemaFailure,
        resolvedBodies,
      )
      when (production) {
        is GoalPlanningPhaseProduction.Stopped -> {
          recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
          return production
        }

        is GoalPlanningPhaseProduction.SchemaRejected -> {
          recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
          priorSchemaFailure = production.reason
          lastRejection = production.reason
          return@repeat
        }

        is GoalPlanningPhaseProduction.EmptyProviderTurn -> {
          recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
          recordEmptyProviderTurn(shared, phaseId, subtask, attempt, production)
          // Deliberately leaves priorSchemaFailure untouched: there is no rejected output to
          // remediate, and injecting one would describe output the agent never produced.
          lastRejection = production.reason
          if (attempt < FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS) {
            val backoff = burstSchedule.emptyTurnBackoffAfterAttempt(attempt)
            interruptibleWait(backoff, shared, subtask?.id ?: 0, phaseId)?.let { stoppedOutcome ->
              return GoalPlanningPhaseProduction.Stopped(stoppedOutcome)
            }
          }
          return@repeat
        }

        is GoalPlanningPhaseProduction.Captured -> Unit
      }
      val gated = gateCapturedPayload(production, phaseId, finalizePayload)
      if (gated is GoalPlanningPhaseProduction.Captured) {
        recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.SUCCEEDED)
        return gated
      }
      val gateReason = (gated as GoalPlanningPhaseProduction.SchemaRejected).reason
      recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
      priorSchemaFailure = gateReason
      lastRejection = gateReason
    }
    return GoalPlanningPhaseProduction.Stopped(
      stopped(shared, subtask?.id ?: 0, fixLoopExhaustedReason(phaseId, lastRejection.orEmpty()), phaseId),
    )
  }

  /**
   * Runs the exact bytes that would be checkpointed through the producer projection gate. Returns
   * the captured production when the gate accepts, or the bounded gate detail as a rejection.
   */
  private fun gateCapturedPayload(
    captured: GoalPlanningPhaseProduction.Captured,
    phaseId: String,
    finalizePayload: (String) -> String,
  ): GoalPlanningPhaseProduction {
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
      ?: return GoalPlanningPhaseProduction.Captured(
        canonicalPayload,
        accepted.normalizedOutput,
        // Enrichment revalidates the final payload, but it must not discard evidence captured
        // while structurally repairing the child output before enrichment.
        accepted.repairEvidence ?: captured.repairEvidence,
      )
    return GoalPlanningPhaseProduction.SchemaRejected(gateReason)
  }

  @Suppress("TooGenericExceptionCaught")
  private fun produceAttemptOrStop(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask?,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    phaseId: String,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    priorSchemaFailure: String?,
    resolvedBodies: GoalPlanningResolvedBoundaryBodies,
  ): GoalPlanningPhaseProduction = try {
    produceAttempt(
      shared,
      request,
      subtask,
      runInvariants,
      phaseId,
      recordedOutputs,
      priorSchemaFailure,
      resolvedBodies,
    )
  } catch (error: Exception) {
    GoalPlanningPhaseProduction.Stopped(
      stopped(
        shared,
        subtask?.id ?: 0,
        unexpectedPlanningFailureReason(phaseId, error),
        phaseId,
      ),
    )
  }

  private fun recordEmptyProviderTurn(
    shared: GoalPlanningSharedContext,
    phaseId: String,
    subtask: DecompositionSubtask?,
    attempt: Int,
    production: GoalPlanningPhaseProduction.EmptyProviderTurn,
  ) {
    planningRejectionRecorder.record(
      GoalPlanningRejectionRecord(
        parentWorkflowId = shared.parentWorkflowId,
        issueKey = shared.issueKey,
        dbPathOverride = shared.dbPathOverride,
        phaseId = phaseId,
        subtaskId = subtask?.id ?: 0,
        attempt = attempt,
        rule = EMPTY_PLANNING_HARVEST_RULE,
        reason = production.reason,
        agentId = production.evidence.agentId,
        rawEvidence = production.evidence.rawOutputPreview.orEmpty(),
      ),
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
    "Goal planning '$phaseId' produced no acceptable output on every attempt " +
      "(cap=${FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS}); nothing was checkpointed. " +
      "Last failure: $lastFailure"

  private fun emptyTurnReason(phaseId: String, evidence: GoalPlanningEmptyTurnEvidence): String =
    "Goal planning '$phaseId' agent turn exited cleanly and returned no output. ${evidence.summary()}"

  @Suppress("ReturnCount")
  private fun produceAttempt(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask?,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    phaseId: String,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    priorSchemaFailure: String?,
    resolvedBodies: GoalPlanningResolvedBoundaryBodies,
  ): GoalPlanningPhaseProduction {
    val currentSubtaskId = subtask?.id ?: 0
    planningPauseOutcome(shared, currentSubtaskId, phaseId)?.let { return it }
    // A recovered shared preplan is already settled by the time its bounded projection is parsed here,
    // so an unhandled rejection would crash the goal driver with no Stopped outcome, no blocked_reason
    // and no closed telemetry segment, then crash identically on every resume. Block durably instead.
    val prompt = runCatching {
      composePlanningPrompt(
        shared,
        request,
        subtask,
        runInvariants,
        phaseId,
        recordedOutputs,
        priorSchemaFailure,
        resolvedBodies,
      )
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
    val startedAtNanos = System.nanoTime()
    val outcome = runCatching { launchPlanningAttempt(shared, request, subtask, phaseId, prompt) }
      .getOrElse { error ->
        if (error is GoalRunnerLaunchAuthorizationDeniedException) {
          return planningPauseOutcome(shared, currentSubtaskId, phaseId, error.controlState.pauseReason)
            ?: error("planning pause outcome was unexpectedly absent")
        }
        throw error
      }
    val durationMs = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI
    val stdout = stdoutFor(outcome)
      ?: return emptyOrStopped(outcome, shared, request, currentSubtaskId, phaseId, durationMs)
    return validatePlanningAttemptOutput(stdout, shared, currentSubtaskId, phaseId)
  }

  private fun planningPauseOutcome(
    shared: GoalPlanningSharedContext,
    subtaskId: Int,
    phaseId: String,
    pauseReason: String? = null,
  ): GoalPlanningPhaseProduction.Stopped? {
    val controls = manifestStore.controlState(shared.parentWorkflowId, shared.dbPathOverride)
    if (!controls.requiresPauseBoundary(shared.manifest)) return null
    val reason = pauseReason?.let { " (reason=$it)" }.orEmpty()
    return GoalPlanningPhaseProduction.Stopped(
      stopped(
        shared,
        subtaskId,
        "Goal planning reached a durable pause boundary before launching phase '$phaseId'$reason.",
        phaseId,
        GoalRunnerStopReason.PAUSED,
      ),
    )
  }

  /**
   * Waits [duration] through [timingPort] in [GoalPlanningBurstSchedule.waitSlice] slices so a durable
   * pause or thread interrupt can terminate the sweep without sleeping through the boundary. Never
   * uses Thread APIs; [RuntimeWaitResult.INTERRUPTED] maps to the same Stopped shape as a launch
   * interrupt (blockedReason names interruption; not [unexpectedPlanningFailureReason]).
   */
  private fun interruptibleWait(
    duration: Duration,
    shared: GoalPlanningSharedContext,
    subtaskId: Int,
    phaseId: String,
  ): GoalPlanningSweepOutcome.Stopped? {
    if (duration <= ZERO) return null
    var remaining = duration
    while (remaining > ZERO) {
      planningPauseOutcome(shared, subtaskId, phaseId)?.let { return it.outcome }
      val slice = remaining.coerceAtMost(burstSchedule.waitSlice)
      when (timingPort.wait(slice)) {
        RuntimeWaitResult.COMPLETED -> remaining -= slice
        RuntimeWaitResult.INTERRUPTED -> return stopped(
          shared,
          subtaskId,
          "Goal planning wait was interrupted before launching phase '$phaseId'.",
          phaseId,
        )
      }
    }
    return planningPauseOutcome(shared, subtaskId, phaseId)?.outcome
  }

  private fun validatePlanningAttemptOutput(
    stdout: String,
    shared: GoalPlanningSharedContext,
    subtaskId: Int,
    phaseId: String,
  ): GoalPlanningPhaseProduction = runCatching {
    outputValidator.validatePhaseOutput(stdout, phaseId).requireAcceptedOutput(phaseId)
  }.fold(
    onSuccess = { accepted ->
      val payload = accepted.normalizedOutput.envelope
      if (payload["status"] != "completed") {
        GoalPlanningPhaseProduction.Stopped(
          stopped(shared, subtaskId, unsuccessfulStatusReason(phaseId, payload["status"]), phaseId),
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
      if (error is InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
        GoalPlanningPhaseProduction.SchemaRejected(
          error.payloadFreeReason ?: "Goal planning phase output was rejected by its schema contract.",
        )
      } else {
        GoalPlanningPhaseProduction.Stopped(
          stopped(shared, subtaskId, malformedReason(phaseId, error), phaseId),
        )
      }
    },
  )

  private fun launchPlanningAttempt(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    subtask: DecompositionSubtask?,
    phaseId: String,
    prompt: String,
  ): AgentRunLaunchOutcome {
    request.outputSink.write(AgentRunOutputStream.STDERR, planningProgressMessage(phaseId, subtask))
    return subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = shared.invokedAgentId,
        configuredAgentOverrideId = shared.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = request.issueKey,
          repoRoot = shared.repoRoot,
          subtaskId = subtask?.id,
          dbPathOverride = shared.dbPathOverride,
          timeout = request.planningBudget,
          progressIdleTimeout = request.progressIdleTimeout,
          outputSink = request.outputSink,
          promptOverride = prompt,
          streamOutputForLiveness = true,
          // The authorization write transaction must close before the child is awaited. Wrapping the
          // blocking launch instead held it for the whole planning run, starving lease renewal.
          spawnAuthorization = manifestStore.authorizePlanningLaunch(shared.parentWorkflowId, shared.dbPathOverride),
        ),
      ),
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
    resolvedBodies: GoalPlanningResolvedBoundaryBodies,
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
    return GoalPlanningContextPromptFormatter.append(
      basePrompt,
      shared.planningPacket,
      subtask,
      phaseId,
      resolvedBodies,
    )
  }

  /**
   * Reads the heading ids the settled preplan selected and resolves exactly those bodies. A missing,
   * legacy, or malformed selection degrades to catalog-only rather than falling back to a full-file
   * dump. Resolution failures are not caught here: the resolver already reports unreadable files as
   * unresolved ids, so anything that escapes it is a contract or wiring fault that must surface
   * instead of being laundered into a silently body-less plan phase on every resume.
   */
  private fun resolvedBoundaryBodies(
    shared: GoalPlanningSharedContext,
    preplanPayload: String,
  ): GoalPlanningResolvedBoundaryBodies {
    val selected = selectedBoundaryHeadingIds(preplanPayload)
    if (selected.isEmpty()) return GoalPlanningResolvedBoundaryBodies()
    return boundaryBodyResolver.resolve(
      shared.repoRoot,
      selected,
      GoalPlanningSharedContextPacket.catalogHeadingIds(shared.planningPacket),
    )
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

  private fun preparationStateReadReason(error: Throwable, issueKey: String, subtaskId: Int): String {
    val base = "Goal planning preparation state could not be read: ${error.message.orEmpty()}"
    val recovery = error as? IncompatibleGoalPlanningPreparationRecoveryError ?: return base
    val remedySubtaskId = when {
      subtaskId > 0 -> subtaskId
      recovery.subtaskId > 0 -> recovery.subtaskId
      else -> 1
    }
    return "$base Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, remedySubtaskId)}"
  }

  /**
   * A planning launch that exits zero, reports no failure mode and still harvests nothing is a
   * provider flake, not a bad prompt. It is retried under the same fix-loop cap instead of blocking
   * the goal on its first occurrence, and its launch facts are retained so the recurrence is
   * countable rather than anecdotal.
   */
  private fun emptyOrStopped(
    outcome: AgentRunLaunchOutcome,
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    currentSubtaskId: Int,
    phaseId: String,
    durationMs: Long,
  ): GoalPlanningPhaseProduction {
    val evidence = emptyTurnEvidence(outcome, durationMs)
      ?: return GoalPlanningPhaseProduction.Stopped(
        stopped(shared, currentSubtaskId, exhaustedReason(outcome, request.planningBudget), phaseId),
      )
    return GoalPlanningPhaseProduction.EmptyProviderTurn(emptyTurnReason(phaseId, evidence), evidence)
  }

  private fun emptyTurnEvidence(outcome: AgentRunLaunchOutcome, durationMs: Long): GoalPlanningEmptyTurnEvidence? {
    if (outcome !is AgentRunLaunchFacts) return null
    val cleanExit = !outcome.spawnFailed && !outcome.timedOut && !outcome.interrupted && outcome.exitStatus == 0
    if (!cleanExit) return null
    return GoalPlanningEmptyTurnEvidence(
      agentId = outcome.agent.id,
      durationMs = durationMs,
      exitStatus = outcome.exitStatus,
      inputTokens = outcome.inputTokens,
      outputTokens = outcome.outputTokens,
      assistantEventCount = outcome.assistantEventCount,
      rawOutputPreview = outcome.rawOutputPreview,
    )
  }

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
    // The launcher explains WHY a spawn failed (missing CLI, unreadable executable); dropping that
    // text left the operator with an unactionable "failed to spawn" on a blocked goal.
    facts.spawnFailed -> stderrExcerpt(facts.stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
      ?.let { excerpt -> "the planning agent failed to spawn — $excerpt" }
      ?: "the planning agent failed to spawn"
    facts.timedOut ->
      "the planning agent exhausted its $planningBudget planning budget; " +
        "raise or disable it with --planning-budget-minutes"
    facts.interrupted -> "the planning agent was interrupted"
    facts.exitStatus != null && facts.exitStatus != 0 -> "the planning agent exited with status ${facts.exitStatus}"
    else -> "the planning agent produced no usable output"
  }

  private fun malformedReason(phaseId: String, error: Throwable): String =
    "Goal planning '$phaseId' output failed the schema gate and could not be prepared: ${error.message.orEmpty()}"

  private fun unexpectedPlanningFailureReason(phaseId: String, error: Exception): String =
    "Goal planning '$phaseId' failed before its output could be checkpointed: " +
      "${error::class.simpleName ?: "Exception"}: ${error.message.orEmpty()}"

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
    reason: GoalRunnerStopReason = GoalRunnerStopReason.BLOCKED,
  ): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
    issueKey = shared.issueKey,
    currentSubtaskId = subtaskId,
    reason = reason,
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
    const val EMPTY_PLANNING_HARVEST_RULE = "empty-planning-harvest"
    const val NANOS_PER_MILLI = 1_000_000L
  }
}

/**
 * Recoverability of a saved shared preplan relative to the current governed inputs.
 * [StaleValid] triggers in-run refresh in [DefaultGoalPlanningSweep.prepare].
 */
internal sealed interface GoalPlanningProvenanceRecoverability {
  data class Reuse(val provenance: GoalPlanningContractProvenance) : GoalPlanningProvenanceRecoverability
  data class StaleValid(val provenance: GoalPlanningContractProvenance) : GoalPlanningProvenanceRecoverability
  data object Invalid : GoalPlanningProvenanceRecoverability
}

/**
 * Validity: manifest hash, phase-output schema id, parent-spec self-hash, payload sha, and every
 * selected heading id resolving in [freshCatalogHeadingIds]. Freshness: canonical parent-spec equality.
 * Empty or absent selected headings are vacuously valid.
 */
internal fun classifyGoalPlanningProvenanceRecoverability(
  existing: SharedGoalPreplanCheckpoint?,
  current: GoalPlanningContractProvenance,
  savedParentSpec: String?,
  currentParentSpec: String,
  freshCatalogHeadingIds: Set<String>,
): GoalPlanningProvenanceRecoverability {
  if (existing == null) return GoalPlanningProvenanceRecoverability.Reuse(current)
  val saved = existing.provenance
  val selected = selectedBoundaryHeadingIds(existing.preplanPayload)
  val valid = saved.decompositionManifestHash == current.decompositionManifestHash &&
    saved.phaseOutputContractId == current.phaseOutputContractId &&
    savedParentSpec != null &&
    sha256HexUtf8(savedParentSpec) == saved.parentSpecHash &&
    sha256HexUtf8(existing.preplanPayload) == existing.payloadSha256 &&
    selected.all { headingId -> headingId in freshCatalogHeadingIds }
  if (!valid) return GoalPlanningProvenanceRecoverability.Invalid
  val fresh = GoalPlanningSpecCanonicalization.canonical(savedParentSpec) ==
    GoalPlanningSpecCanonicalization.canonical(currentParentSpec)
  return if (fresh) {
    GoalPlanningProvenanceRecoverability.Reuse(saved)
  } else {
    GoalPlanningProvenanceRecoverability.StaleValid(saved)
  }
}

internal fun selectedBoundaryHeadingIds(preplanPayload: String): List<String> = runCatching {
  JsonSupport.parseObjectOrNull(preplanPayload)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get(SELECTED_BOUNDARY_HEADINGS_FIELD)
    ?.let { value -> (value as? List<*>)?.mapNotNull { id -> (id as? String)?.takeIf(String::isNotBlank) } }
}.getOrNull().orEmpty()

private const val SELECTED_BOUNDARY_HEADINGS_FIELD: String = "selected_boundary_headings"

/**
 * Durable evidence seam for a planning attempt the run rejected without any output to gate. Kept
 * separate from [GoalPlanningAttemptRecorder], which counts attempts but retains no launch facts.
 */
fun interface GoalPlanningRejectionRecorder {
  fun record(record: GoalPlanningRejectionRecord)

  companion object {
    val NONE: GoalPlanningRejectionRecorder = GoalPlanningRejectionRecorder {}
  }
}

@Inject
class DurableGoalPlanningRejectionRecorder(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) : GoalPlanningRejectionRecorder {
  override fun record(record: GoalPlanningRejectionRecord) {
    // Evidence must never decide the run's outcome: a diagnostics failure leaves the planning
    // rejection exactly as classified rather than converting it into a crash.
    runCatching {
      recorder.recordRejectedOutput(
        RejectedOutputDiagnosticRequest(
          workflowId = record.parentWorkflowId,
          phaseId = record.phaseId,
          attempt = record.attempt.coerceAtLeast(1),
          rule = record.rule,
          path = "/",
          reason = record.reason,
          agentId = record.agentId,
          model = "unspecified",
          rawResponse = record.rawEvidence.encodeToByteArray(),
        ),
        record.dbPathOverride,
      )
    }
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
