package skillbill.application.goalrunner.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState

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

internal fun incompatibleProvenance(
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
