package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

@Suppress("ReturnCount")
internal fun DefaultGoalPlanningSweep.produceMissingPlans(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  identity: GoalPlanningIdentity,
  provenance: GoalPlanningContractProvenance,
  sharedCheckpoint: SharedGoalPreplanCheckpoint,
  activeSubtasks: List<DecompositionSubtask>,
): GoalPlanningSweepOutcome {
  val descriptors = runCatching {
    activeSubtasks.mapIndexed { order, subtask -> descriptor(shared, subtask, order) }
  }
    .getOrElse { error ->
      return stopped(
        shared,
        0,
        "Goal planning governed subtask provenance could not be computed: ${error.message.orEmpty()}",
      )
    }
  val subtasksById = activeSubtasks.associateBy(DecompositionSubtask::id)
  val resolvedBodies = GoalPlanningResolvedBoundaryBodies()
  var plansLaunchedThisPrepare = 0
  while (true) {
    val recovery = runCatching {
      checkpoint.recoveryProgress(identity, descriptors, provenance, shared.dbPathOverride).firstMissingSubtaskId
    }
    recovery.exceptionOrNull()?.let { error ->
      val subtaskId = recoverySubtaskId(error)
      val phaseId = GoalPlanningSweepConstants.PHASE_PLAN.takeIf { subtaskId != 0 }
        ?: GoalPlanningSweepConstants.PHASE_PREPLAN
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
      interruptibleWait(burstSchedule.planLaunchPace, shared, missingId, GoalPlanningSweepConstants.PHASE_PLAN)
        ?.let { return it }
    }
    producePlan(shared, request, subtask, descriptor, provenance, sharedCheckpoint.preplanPayload, resolvedBodies)
      ?.let { return it }
    plansLaunchedThisPrepare += 1
  }
}

internal fun DefaultGoalPlanningSweep.producePlan(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  subtask: DecompositionSubtask,
  descriptor: GovernedGoalSubtaskDescriptor,
  provenance: GoalPlanningContractProvenance,
  preplanPayload: String,
  resolvedBodies: GoalPlanningResolvedBoundaryBodies,
): GoalPlanningSweepOutcome.Stopped? {
  val resolvedSpecPath = resolvedSubSpecPath(shared.repoRoot, subtask.specPath)
    ?: return stopped(shared, subtask.id, unresolvedSpecReason(subtask), GoalPlanningSweepConstants.PHASE_PLAN)
  val runInvariants = runCatching { invariantsSource.read(resolvedSpecPath) }.getOrElse { error ->
    return stopped(shared, subtask.id, invariantReadReason(subtask, error), GoalPlanningSweepConstants.PHASE_PLAN)
  }
  val planProduction = producePhase(
    shared,
    request,
    subtask,
    runInvariants,
    GoalPlanningSweepConstants.PHASE_PLAN,
    listOf(FeatureTaskRuntimePhaseOutput(GoalPlanningSweepConstants.PHASE_PREPLAN, 1, preplanPayload)),
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
    onFailure = { error -> stopped(shared, subtask.id, persistenceReason(subtask, error), GoalPlanningSweepConstants.PHASE_PLAN) },
  )
}

internal fun DefaultGoalPlanningSweep.descriptor(
  shared: GoalPlanningSharedContext,
  subtask: DecompositionSubtask,
  order: Int,
): GovernedGoalSubtaskDescriptor {
  val path = resolvedSubSpecPath(shared.repoRoot, subtask.specPath) ?: error(unresolvedSpecReason(subtask))
  val governedPath = shared.repoRoot.relativize(path).joinToString("/")
  val identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity)
  val recovered = checkpoint.findStoredSubtaskPlan(
    identity,
    subtask.id,
    governedPath,
    shared.dbPathOverride,
  )
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
