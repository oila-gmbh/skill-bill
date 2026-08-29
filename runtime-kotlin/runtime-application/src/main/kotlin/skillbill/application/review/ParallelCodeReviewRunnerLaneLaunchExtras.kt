package skillbill.application.review

import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import java.nio.file.Path

internal fun ParallelCodeReviewRunnerLaneLaunch.mergedBudget(
  selected: List<ReviewSpecialistLaunchRequest>,
): ReviewContextBudgetPolicy {
  val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
  return primary.budget.copy(
    maxLaneEvidenceBytes = selected.sumOf { it.budget.maxLaneEvidenceBytes },
    maxSpecialistToolCalls = primary.budget.maxSpecialistToolCalls * selected.size,
    maxAssignmentExpansions = primary.budget.maxAssignmentExpansions * selected.size,
  )
}

internal fun ParallelCodeReviewRunnerLaneLaunch.mergedBundle(
  packet: ReviewContextPacket,
  assignedHunks: Set<String>,
): ReviewLaneBundle = ReviewLaneBundle(
  packet.commitUnits.sortedBy { it.orderIndex }.mapNotNull { unit ->
    unit.hunkIds.filter { it in assignedHunks }
      .takeIf { it.isNotEmpty() }
      ?.let { ReviewLaneBundleEntry(unit.commitSha, unit.orderIndex, it) }
  },
)

internal fun ParallelCodeReviewRunnerLaneLaunch.parentBrokerBinding(
  selected: List<ReviewSpecialistLaunchRequest>,
  repoRoot: Path,
): ReviewEvidenceBrokerBinding {
  val primary = selected.minByOrNull { it.assignment.laneDecision.orderIndex } ?: selected.first()
  if (selected.size == 1) return brokerBinding(primary, repoRoot)
  val assignedPaths = selected.flatMap { it.assignment.assignedPaths }.distinct()
  val assignedHunks = selected.flatMap { it.assignment.assignedHunks }.distinct()
  val expansions = selected.flatMap { it.assignment.expansions }.distinctBy { it.expansionId }
  val assigned = assignedHunks.toSet()
  val merged = primary.assignment.copy(
    laneRouting = emptyList(),
    assignedPaths = assignedPaths,
    assignedHunks = assignedHunks,
    assignedBundle = mergedBundle(primary.packet, assigned),
    evidenceTargets = selected.flatMap { it.assignment.evidenceTargets }.distinctBy { it.targetId },
    dependencyAllowlist = ReviewDependencyAllowlist(
      selected.flatMap { it.assignment.dependencyAllowlist.normalized }
        .distinct()
        .filterNot { it in assignedPaths.toSet() },
    ),
    expansions = expansions,
  )
  return ReviewEvidenceBrokerBinding(
    repoRoot = repoRoot,
    assignment = merged,
    laneRubricId = primary.rubrics.first().rubricId,
    budget = mergedBudget(selected),
    namedDependencies = selected.flatMap { it.namedDependencies }.toSet(),
    trustedExpansionLedger = expansions,
    projectedHunks = primary.packet.changedHunks.filter { it.hunkId in assigned },
    locatorReader = sharedEvidenceLocatorReader,
    bodyExtractor = ReviewLocatorHunkBodyExtractor,
  )
}

internal fun ParallelCodeReviewRunnerLaneLaunch.brokerBinding(
  launch: ReviewSpecialistLaunchRequest,
  repoRoot: Path,
): ReviewEvidenceBrokerBinding {
  val assigned = launch.assignment.assignedHunks.toSet()
  return ReviewEvidenceBrokerBinding(
    repoRoot = repoRoot,
    assignment = launch.assignment,
    laneRubricId = launch.rubrics.first().rubricId,
    budget = launch.budget,
    namedDependencies = launch.namedDependencies,
    trustedExpansionLedger = launch.assignment.expansions,
    projectedHunks = launch.packet.changedHunks.filter { it.hunkId in assigned },
    locatorReader = sharedEvidenceLocatorReader,
    bodyExtractor = ReviewLocatorHunkBodyExtractor,
  )
}
