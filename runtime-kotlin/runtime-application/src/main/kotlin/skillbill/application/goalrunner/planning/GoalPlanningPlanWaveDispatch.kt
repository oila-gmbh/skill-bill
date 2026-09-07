package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.ProduceMissingPlansArgs
import skillbill.application.goalrunner.ProducePlanArgs
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.decomposition.model.DecompositionSubtask

private data class MissingPlanSet(
  val subtaskIds: List<Int> = emptyList(),
  val outcome: GoalPlanningSweepOutcome? = null,
)

private data class PlanWaveArgs(
  val produce: ProduceMissingPlansArgs,
  val descriptors: List<GovernedGoalSubtaskDescriptor>,
  val subtasksById: Map<Int, DecompositionSubtask>,
  val resolvedBodies: GoalPlanningResolvedBoundaryBodies,
  val wave: List<Int>,
)

internal fun DefaultGoalPlanningSweep.produceMissingPlansLoop(
  args: ProduceMissingPlansArgs,
  descriptors: List<GovernedGoalSubtaskDescriptor>,
): GoalPlanningSweepOutcome {
  val missing = missingPlanSet(args, descriptors)
  missing.outcome?.let { return it }
  return dispatchPlanWaves(args, descriptors, missing.subtaskIds)
    ?: GoalPlanningSweepOutcome.PreparedAll(args.identity, args.provenance, descriptors)
}

private fun DefaultGoalPlanningSweep.missingPlanSet(
  args: ProduceMissingPlansArgs,
  descriptors: List<GovernedGoalSubtaskDescriptor>,
): MissingPlanSet {
  val shared = args.shared
  val recovery = runCatching {
    checkpoint.recoveryProgress(
      args.identity,
      descriptors,
      args.provenance,
      shared.dbPathOverride,
    ).missingSubtaskIds
  }
  val error = recovery.exceptionOrNull() ?: return MissingPlanSet(subtaskIds = recovery.getOrThrow())
  val subtaskId = recoverySubtaskId(error)
  val phaseId = GoalPlanningSweepConstants.PHASE_PLAN.takeIf { subtaskId != 0 }
    ?: GoalPlanningSweepConstants.PHASE_PREPLAN
  return MissingPlanSet(
    outcome = stopped(
      shared,
      subtaskId,
      preparationStateReadReason(error, shared.issueKey, subtaskId),
      phaseId,
    ),
  )
}

private fun DefaultGoalPlanningSweep.dispatchPlanWaves(
  args: ProduceMissingPlansArgs,
  descriptors: List<GovernedGoalSubtaskDescriptor>,
  missingSubtaskIds: List<Int>,
): GoalPlanningSweepOutcome? {
  val shared = args.shared
  val phasePlan = GoalPlanningSweepConstants.PHASE_PLAN
  val waveArgs = PlanWaveArgs(
    produce = args,
    descriptors = descriptors,
    subtasksById = args.activeSubtasks.associateBy(DecompositionSubtask::id),
    resolvedBodies = GoalPlanningResolvedBoundaryBodies(),
    wave = emptyList(),
  )
  val waves = missingSubtaskIds.chunked(burstSchedule.planFanOutCap)
  var outcome: GoalPlanningSweepOutcome? = null
  for ((index, wave) in waves.withIndex()) {
    val nextWaveFirstId = waves.getOrNull(index + 1)?.first()
    outcome = planningPauseOutcome(shared, wave.first(), phasePlan)?.outcome
      ?: runPlanWave(waveArgs.copy(wave = wave))
      ?: nextWaveFirstId?.let { planningPauseOutcome(shared, it, phasePlan)?.outcome }
    if (outcome != null) break
  }
  return outcome
}

private fun DefaultGoalPlanningSweep.runPlanWave(args: PlanWaveArgs): GoalPlanningSweepOutcome.Stopped? {
  val units = args.wave.map { subtaskId -> { producePlanUnit(args, subtaskId) } }
  val results = fanOutPort.runBounded(burstSchedule.planFanOutCap, units)
  return args.wave.indices.firstNotNullOfOrNull { index ->
    results[index].getOrElse { error ->
      stopped(
        args.produce.shared,
        args.wave[index],
        unexpectedPlanningFailureReason(GoalPlanningSweepConstants.PHASE_PLAN, error),
        GoalPlanningSweepConstants.PHASE_PLAN,
      )
    }
  }
}

private fun DefaultGoalPlanningSweep.producePlanUnit(
  args: PlanWaveArgs,
  subtaskId: Int,
): GoalPlanningSweepOutcome.Stopped? {
  val produce = args.produce
  val shared = produce.shared
  val subtask = args.subtasksById[subtaskId]
    ?: return stopped(shared, subtaskId, noSuchSubtaskReason(subtaskId))
  val sink = SubtaskAttributedOutputSink(fanOutPort, produce.request.outputSink, subtaskId)
  return try {
    producePlan(
      ProducePlanArgs(
        shared = shared,
        request = produce.request,
        subtask = subtask,
        descriptor = args.descriptors.single { it.subtaskId == subtaskId },
        provenance = produce.provenance,
        preplanPayload = produce.sharedCheckpoint.preplanPayload,
        resolvedBodies = args.resolvedBodies,
        outputSink = sink,
      ),
    )
  } finally {
    sink.flushTrailingLines()
  }
}

/**
 * Forwards one planning unit's streamed output to the shared sink one whole line at a time, tagged
 * with the subtask that produced it. The provider transport hands over arbitrary chunks from a
 * stdout and a stderr drain, so partial lines are buffered per stream and only completed lines
 * reach the delegate; every delegate write goes through the fan-out port's mutual exclusion so a
 * line from one unit cannot land inside a line from another.
 */
private class SubtaskAttributedOutputSink(
  private val fanOutPort: BoundedWorkFanOutPort,
  private val delegate: AgentRunOutputSink,
  subtaskId: Int,
) : AgentRunOutputSink {
  private val attribution = "[subtask $subtaskId] "
  private val pending = mutableMapOf<AgentRunOutputStream, StringBuilder>()

  override fun write(stream: AgentRunOutputStream, text: String) = fanOutPort.runExclusively {
    val buffer = pending.getOrPut(stream) { StringBuilder() }.append(text)
    var newline = buffer.indexOf("\n")
    while (newline >= 0) {
      val line = buffer.substring(0, newline + 1)
      buffer.delete(0, newline + 1)
      delegate.write(stream, attribution + line)
      newline = buffer.indexOf("\n")
    }
  }

  fun flushTrailingLines() = fanOutPort.runExclusively {
    pending.forEach { (stream, buffer) ->
      if (buffer.isNotEmpty()) {
        delegate.write(stream, attribution + buffer + "\n")
        buffer.setLength(0)
      }
    }
  }
}
