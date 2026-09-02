package skillbill.application.goalrunner.planning
import skillbill.application.goalplanning.sha256HexUtf8
import skillbill.application.goalrunner.ProduceMissingPlansArgs
import skillbill.application.goalrunner.ProducePlanArgs
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

private data class MissingSubtaskResolution(
  val missingId: Int? = null,
  val outcome: GoalPlanningSweepOutcome? = null,
)

internal fun DefaultGoalPlanningSweep.produceMissingPlans(args: ProduceMissingPlansArgs): GoalPlanningSweepOutcome {
  val shared = args.shared
  val descriptors = runCatching {
    args.activeSubtasks.mapIndexed { order, subtask -> descriptor(shared, subtask, order) }
  }.getOrElse { error ->
    return stopped(
      shared,
      0,
      "Goal planning governed subtask provenance could not be computed: ${error.message.orEmpty()}",
    )
  }
  return produceMissingPlansLoop(args, descriptors)
}

private fun DefaultGoalPlanningSweep.produceMissingPlansLoop(
  args: ProduceMissingPlansArgs,
  descriptors: List<GovernedGoalSubtaskDescriptor>,
): GoalPlanningSweepOutcome {
  val subtasksById = args.activeSubtasks.associateBy(DecompositionSubtask::id)
  val resolvedBodies = GoalPlanningResolvedBoundaryBodies()
  var plansLaunchedThisPrepare = 0
  while (true) {
    val outcome = produceOneMissingPlan(
      args,
      descriptors,
      subtasksById,
      resolvedBodies,
      plansLaunchedThisPrepare,
    )
    if (outcome != null) return outcome
    plansLaunchedThisPrepare += 1
  }
}

private fun DefaultGoalPlanningSweep.firstMissingSubtaskId(
  args: ProduceMissingPlansArgs,
  descriptors: List<GovernedGoalSubtaskDescriptor>,
): MissingSubtaskResolution {
  val shared = args.shared
  val recovery = runCatching {
    checkpoint.recoveryProgress(
      args.identity,
      descriptors,
      args.provenance,
      shared.dbPathOverride,
    ).firstMissingSubtaskId
  }
  recovery.exceptionOrNull()?.let { error ->
    val subtaskId = recoverySubtaskId(error)
    val phaseId = GoalPlanningSweepConstants.PHASE_PLAN.takeIf { subtaskId != 0 }
      ?: GoalPlanningSweepConstants.PHASE_PREPLAN
    return MissingSubtaskResolution(
      outcome = stopped(
        shared,
        subtaskId,
        preparationStateReadReason(error, shared.issueKey, subtaskId),
        phaseId,
      ),
    )
  }
  val missingId = recovery.getOrThrow()
  if (missingId == null) {
    return MissingSubtaskResolution(
      outcome = GoalPlanningSweepOutcome.PreparedAll(args.identity, args.provenance, descriptors),
    )
  }
  return MissingSubtaskResolution(missingId = missingId)
}

private fun DefaultGoalPlanningSweep.produceOneMissingPlan(
  args: ProduceMissingPlansArgs,
  descriptors: List<GovernedGoalSubtaskDescriptor>,
  subtasksById: Map<Int, DecompositionSubtask>,
  resolvedBodies: GoalPlanningResolvedBoundaryBodies,
  plansLaunchedThisPrepare: Int,
): GoalPlanningSweepOutcome? {
  val shared = args.shared
  val resolution = firstMissingSubtaskId(args, descriptors)
  if (resolution.outcome != null) return resolution.outcome
  val missingId = requireNotNull(resolution.missingId)
  val subtask = subtasksById[missingId]
    ?: return stopped(shared, missingId, noSuchSubtaskReason(missingId))
  val descriptor = descriptors.single { it.subtaskId == missingId }
  if (plansLaunchedThisPrepare > 0) {
    val paced = interruptibleWait(
      burstSchedule.planLaunchPace,
      shared,
      missingId,
      GoalPlanningSweepConstants.PHASE_PLAN,
    )
    if (paced != null) return paced
  }
  return producePlan(
    ProducePlanArgs(
      shared = shared,
      request = args.request,
      subtask = subtask,
      descriptor = descriptor,
      provenance = args.provenance,
      preplanPayload = args.sharedCheckpoint.preplanPayload,
      resolvedBodies = resolvedBodies,
    ),
  )
}

internal fun DefaultGoalPlanningSweep.producePlan(args: ProducePlanArgs): GoalPlanningSweepOutcome.Stopped? {
  val shared = args.shared
  val request = args.request
  val subtask = args.subtask
  val descriptor = args.descriptor
  val provenance = args.provenance
  val preplanPayload = args.preplanPayload
  val resolvedBodies = args.resolvedBodies
  val resolvedSpecPath = resolvedSubSpecPath(shared.repoRoot, subtask.specPath)
    ?: return stopped(shared, subtask.id, unresolvedSpecReason(subtask), GoalPlanningSweepConstants.PHASE_PLAN)
  val runInvariants = runCatching { invariantsSource.read(resolvedSpecPath) }.getOrElse { error ->
    return stopped(shared, subtask.id, invariantReadReason(subtask, error), GoalPlanningSweepConstants.PHASE_PLAN)
  }
  val planProduction = producePhase(
    GoalPlanningProducePhaseArgs(
      attempt = GoalPlanningProduceAttemptArgs(
        phase = GoalPlanningPhaseContext(
          shared = shared,
          request = request,
          subtask = subtask,
          runInvariants = runInvariants,
          phaseId = GoalPlanningSweepConstants.PHASE_PLAN,
        ),
        recordedOutputs = listOf(
          FeatureTaskRuntimePhaseOutput(GoalPlanningSweepConstants.PHASE_PREPLAN, 1, preplanPayload),
        ),
        resolvedBodies = resolvedBodies,
      ),
    ),
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
    onFailure = { error ->
      stopped(
        shared,
        subtask.id,
        persistenceReason(subtask, error),
        GoalPlanningSweepConstants.PHASE_PLAN,
      )
    },
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
