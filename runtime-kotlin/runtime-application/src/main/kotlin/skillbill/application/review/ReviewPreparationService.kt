package skillbill.application.review

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewPreparationResult
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.model.ReviewFactPorts
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewContextBudgetExceeded
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewEvidenceTarget
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleEntry
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewRuleReference

private data class ResolvedReviewFacts(
  val scope: ReviewScopeFacts,
  val routing: ReviewStackRoutingFacts,
  val matchedRules: List<ReviewRuleReference>,
  val learningsReferences: List<ReviewLearningsReference>,
  val buildTestFacts: List<ReviewBuildTestFact>,
  val laneDecisions: List<ReviewLaneDecision>,
  val routingMatrix: ReviewCommitLaneRoutingMatrix,
)

class ReviewPreparationService(
  private val ports: ReviewFactPorts,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
  private val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
) {
  fun prepare(request: ReviewPreparationRequest): ReviewPreparationResult {
    request.specIntentProjection?.let { enforceSpecIntentProjectionBudget(it, budget) }
    val scope = ports.scope.resolveScope(request.reviewId)
    val routing = ports.stackRouting.resolveStackRouting(scope)
    val matchedRules = ports.guidance.resolveMatchedRules(scope, routing)
    val learningsReferences = ports.learnings.resolveLearnings(scope, routing)
    val facts = ports.buildTestFacts.resolveBuildTestFacts(scope)
    val selection = ports.laneSelection.decideLanes(scope, routing)
    val laneDecisions = selection.decisions

    val resolved = ResolvedReviewFacts(
      scope,
      routing,
      matchedRules,
      learningsReferences,
      facts,
      laneDecisions,
      selection.routingMatrix,
    )
    val packet = composePacket(request, resolved)
    val assignments = composeAssignments(request, packet, laneDecisions)
    validateAgainstPacket(packet, assignments)

    val packetEnvelope = packet.toParentPacketEnvelope()
    envelopeValidator.validate(packetEnvelope.asWireMap(), parentLabel(packet))
    val assignmentEnvelopes = assignments.map { assignment ->
      assignment.toAssignmentEnvelope()
        .also { envelopeValidator.validate(it.asWireMap(), assignmentLabel(assignment)) }
    }

    return ReviewPreparationResult(packet, assignments, packetEnvelope, assignmentEnvelopes)
  }

  fun validateAgainstPacket(packet: ReviewContextPacket, assignments: List<ReviewAssignment>) {
    if (assignments.map { it.lane }.distinct().size != assignments.size) {
      reject(parentLabel(packet), "Assignments contain duplicate lanes.")
    }
    if (assignments.size != packet.selectedLanes.size) {
      reject(
        parentLabel(packet),
        "Review must assign exactly one specialist lane per selected lane (${packet.selectedLanes.size}); " +
          "synthesized ${assignments.size} assignment(s). Commit or segment count must not multiply lanes.",
      )
    }
    if (assignments.map { it.lane }.toSet() != packet.selectedLanes.toSet()) {
      reject(parentLabel(packet), "Assignments must cover exactly the packet's selected lanes.")
    }
    val knownAssignmentDigests = assignments.map { it.digest }.toSet()
    assignments.forEach { assignment ->
      rejectRevisionDrift(packet, assignment)
      rejectOwnershipViolations(packet, assignment)
      rejectBundleViolations(packet, assignment)
      if (assignment.expansions.size > budget.maxAssignmentExpansions) {
        throw ReviewContextBudgetExceededException(
          ReviewContextBudgetExceeded(
            lane = assignment.lane,
            budgetKind = "assignment_expansions",
            configuredLimit = budget.maxAssignmentExpansions.toLong(),
            observedValue = assignment.expansions.size.toLong(),
            packetDigest = packet.digest,
            assignmentDigest = assignment.digest,
            enforceable = true,
          ),
        )
      }
      rejectUnknownAssignmentDigests(
        assignmentLabel(assignment),
        assignment.expansions,
        knownAssignmentDigests,
        "Assignment expansion records",
      )
    }
    rejectUnknownAssignmentDigests(
      parentLabel(packet),
      packet.expansionLedger,
      knownAssignmentDigests,
      "Packet expansion ledger records",
    )
  }

  private fun composePacket(request: ReviewPreparationRequest, resolved: ResolvedReviewFacts): ReviewContextPacket {
    val laneDecisions = resolved.laneDecisions
    if (laneDecisions.map { it.lane }.distinct().size != laneDecisions.size) {
      reject(request.reviewId, "Lane selection returned duplicate lane decisions.")
    }
    val includedLanes = laneDecisions.filter { it.included }
      .sortedWith(compareBy(ReviewLaneDecision::orderIndex, ReviewLaneDecision::lane))
      .map { it.lane }
    if (includedLanes.isEmpty()) {
      reject(request.reviewId, "Lane selection produced no included lane; a review packet needs at least one lane.")
    }

    val packet = ReviewContextPacket(
      reviewId = request.reviewId,
      repositoryIdentity = resolved.scope.repositoryIdentity,
      baseRevision = resolved.scope.baseRevision,
      headRevision = resolved.scope.headRevision,
      status = resolved.scope.status,
      stack = resolved.routing.stack,
      pack = resolved.routing.pack,
      addOns = resolved.routing.addOns,
      composedLayers = resolved.routing.composedLayers,
      selectedLanes = includedLanes,
      changedHunks = resolved.scope.changedHunks,
      commitUnits = resolved.scope.commitUnits,
      coverageFact = resolved.scope.coverageFact,
      routingMatrix = resolved.routingMatrix,
      reviewRevision = request.reviewRevision,
      laneDecisions = resolved.laneDecisions,
      matchedRules = resolved.matchedRules,
      learningsReferences = resolved.learningsReferences,
      buildTestFacts = resolved.buildTestFacts,
      dependencyAllowlist = request.dependencyAllowlist,
      baselineUntrackedPolicy = request.baselineUntrackedPolicy,
      evidenceTargets = evidenceTargetsFor(resolved.scope.changedHunks),
    )

    val overlap = packet.dependencyAllowlist.normalized.filter { it in packet.ownedPaths }
    if (overlap.isNotEmpty()) {
      reject(
        request.reviewId,
        "Dependency-allowlist entries overlap changed paths owned by the packet: ${overlap.sorted()}.",
      )
    }
    if (packet.canonicalBytes > budget.maxParentPacketBytes) {
      throw ReviewContextBudgetExceededException(
        ReviewContextBudgetExceeded(
          lane = includedLanes.first(),
          budgetKind = "parent_packet_bytes",
          configuredLimit = budget.maxParentPacketBytes,
          observedValue = packet.canonicalBytes,
          packetDigest = packet.digest,
          assignmentDigest = packet.digest,
          enforceable = true,
        ),
      )
    }
    return packet
  }

  private fun composeAssignments(
    request: ReviewPreparationRequest,
    packet: ReviewContextPacket,
    laneDecisions: List<ReviewLaneDecision>,
  ): List<ReviewAssignment> {
    val packetDigest = packet.digest

    /** The full commit-grouped projection of a lane's already-assigned hunks, in packet commit order. */
    fun laneBundle(laneHunkIds: Set<String>) = ReviewLaneBundle(
      packet.commitUnits.sortedBy { it.orderIndex }.mapNotNull { unit ->
        unit.hunkIds.filter { it in laneHunkIds }
          .takeIf { it.isNotEmpty() }
          ?.let { ReviewLaneBundleEntry(unit.commitSha, unit.orderIndex, it) }
      },
    )

    return packet.selectedLanes.map { lane ->
      val decision = laneDecisions.first { it.lane == lane }
      val unowned = decision.normalizedOwnedPaths.filterNot { it in packet.ownedPaths }
      if (unowned.isNotEmpty()) {
        reject(request.reviewId, "Lane '$lane' claims paths the packet does not own: ${unowned.sorted()}.")
      }
      // Sparse routing narrows the lane to the commits it focused, so a path this lane owns
      // contributes only the hunks the focused commits introduced there.
      val laneHunkIds = packet.focusedHunkIds(decision).sorted()
      val lanePaths = decision.normalizedOwnedPaths.sorted()
      ReviewAssignment(
        assignedBundle = laneBundle(laneHunkIds.toSet()),
        laneRouting = packet.routingMatrix.decisionsFor(lane),
        reviewId = packet.reviewId,
        packetDigest = packetDigest,
        lane = lane,
        baseRevision = packet.baseRevision,
        headRevision = packet.headRevision,
        assignedPaths = lanePaths,
        assignedHunks = laneHunkIds,
        criteriaReferences = request.criteriaReferences[lane].orEmpty(),
        matchedRules = packet.matchedRules,
        evidenceTargets = packet.evidenceTargets.filter { it.path in lanePaths },
        reviewRevision = packet.reviewRevision,
        laneDecision = decision,
        dependencyAllowlist = packet.dependencyAllowlist,
        baselineUntrackedPolicy = packet.baselineUntrackedPolicy,
      )
    }
  }

  private fun rejectBundleViolations(packet: ReviewContextPacket, assignment: ReviewAssignment) {
    val label = assignmentLabel(assignment)
    val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
    val outside = assignment.assignedBundle.entries.map { it.commitSha }.filterNot { it in unitsBySha }
    if (outside.isNotEmpty()) {
      reject(label, "Assignment claims commit units the packet does not own: ${outside.sorted()}.")
    }
    assignment.assignedBundle.entries.forEach { entry ->
      val unit = unitsBySha.getValue(entry.commitSha)
      if (entry.orderIndex != unit.orderIndex) {
        reject(label, "Assignment bundle entry '${entry.commitSha}' diverges from the packet commit order.")
      }
      val stray = entry.hunkIds.filterNot { it in unit.hunkIds }
      if (stray.isNotEmpty()) {
        reject(label, "Assignment bundle attributes hunks to '${entry.commitSha}' that the commit does not own.")
      }
    }
    if (assignment.assignedBundle.hunkIds.toSet() != assignment.assignedHunks.toSet()) {
      reject(label, "Assignment bundle does not cover exactly the assigned hunks for '${assignment.lane}'.")
    }
  }

  /**
   * Relevance was decided once, on the parent: a lane sees exactly the hunks its focused commits
   * introduced under its owned paths. Claiming a skipped commit's hunk is a routing violation, not
   * a widening a worker is allowed to make.
   */
  private fun rejectRoutingViolations(
    packet: ReviewContextPacket,
    assignment: ReviewAssignment,
    expectedPaths: Set<String>,
  ) {
    val label = assignmentLabel(assignment)
    val laneRouting = packet.routingMatrix.decisionsFor(assignment.lane)
    if (assignment.laneRouting != laneRouting) {
      reject(label, "Assignment lane routing differs from the packet routing matrix for '${assignment.lane}'.")
    }
    val focused = packet.routingMatrix.focusedCommits(assignment.lane).toSet()
    val hunkOwners = packet.commitUnits.flatMap { unit -> unit.hunkIds.map { it to unit.commitSha } }.toMap()
    val fromSkipped = assignment.assignedHunks.filter { hunkOwners[it] !in focused }
    if (fromSkipped.isNotEmpty()) {
      val commits = fromSkipped.mapNotNull { hunkOwners[it] }.distinct().sorted()
      reject(
        label,
        "Assignment claims hunks from commits routing skipped for '${assignment.lane}': $commits.",
      )
    }
    val expectedHunks = packet.focusedHunkIds(assignment.laneDecision)
    if (assignment.assignedHunks.toSet() != expectedHunks) {
      reject(label, "Assignment hunks differ from the focused-commit hunks routed to '${assignment.lane}'.")
    }
    val coveredPaths = packet.changedHunks.filter { it.hunkId in expectedHunks }.map { it.path }.toSet()
    if (coveredPaths != expectedPaths) {
      reject(
        label,
        "Assignment paths for '${assignment.lane}' are not exactly the paths its focused commits changed.",
      )
    }
  }

  private fun rejectRevisionDrift(packet: ReviewContextPacket, assignment: ReviewAssignment) {
    val label = assignmentLabel(assignment)
    if (assignment.reviewId != packet.reviewId) {
      reject(label, "Assignment review id '${assignment.reviewId}' does not match packet '${packet.reviewId}'.")
    }
    if (assignment.packetDigest != packet.digest) {
      reject(
        label,
        "Assignment carries packet digest '${assignment.packetDigest}' but the packet recomputes to " +
          "'${packet.digest}'; the assignment belongs to a different review revision.",
      )
    }
    if (assignment.reviewRevision != packet.reviewRevision) {
      reject(
        label,
        "Assignment review revision '${assignment.reviewRevision.canonical}' does not match packet revision " +
          "'${packet.reviewRevision.canonical}'.",
      )
    }
    if (assignment.baseRevision != packet.baseRevision || assignment.headRevision != packet.headRevision) {
      reject(label, "Assignment revisions do not match the packet base/head revisions.")
    }
    if (assignment.baselineUntrackedPolicy != packet.baselineUntrackedPolicy) {
      reject(label, "Assignment baseline-untracked policy differs from the packet policy.")
    }
    if (assignment.lane !in packet.selectedLanes) {
      reject(label, "Assignment lane '${assignment.lane}' is not a selected lane of the packet.")
    }
    val packetDecision = packet.laneDecisions.single { it.lane == assignment.lane }
    if (assignment.laneDecision != packetDecision) {
      reject(label, "Assignment lane decision differs from the packet decision for '${assignment.lane}'.")
    }
  }

  private fun rejectOwnershipViolations(packet: ReviewContextPacket, assignment: ReviewAssignment) {
    val label = assignmentLabel(assignment)
    val ownedPaths = packet.ownedPaths
    val ownedHunkIds = packet.ownedHunkIds
    val unownedPaths = assignment.assignedPaths.filterNot { it in ownedPaths }
    if (unownedPaths.isNotEmpty()) {
      reject(label, "Assignment claims paths not owned by the packet: ${unownedPaths.sorted()}.")
    }
    val expectedPaths = assignment.laneDecision.normalizedOwnedPaths.toSet()
    val actualPaths = assignment.assignedPaths.toSet()
    if (actualPaths != expectedPaths) {
      reject(label, "Assignment paths differ from the packet decision for '${assignment.lane}'.")
    }
    val unownedHunks = assignment.assignedHunks.filterNot { it in ownedHunkIds }
    if (unownedHunks.isNotEmpty()) {
      reject(label, "Assignment claims hunk ids not owned by the packet: ${unownedHunks.sorted()}.")
    }
    rejectRoutingViolations(packet, assignment, expectedPaths)
    val allowlist = packet.dependencyAllowlist.normalized.toSet()
    val escaping = assignment.dependencyAllowlist.normalized.filterNot { it in allowlist }
    if (escaping.isNotEmpty()) {
      reject(label, "Assignment dependency allowlist escapes the packet allowlist: ${escaping.sorted()}.")
    }
    val unknownRules = assignment.matchedRules.filterNot { it in packet.matchedRules }
    if (unknownRules.isNotEmpty()) {
      reject(label, "Assignment matched rules are not packet-owned: ${unknownRules.map { it.ruleId }.sorted()}.")
    }
    if (assignment.matchedRules.toSet() != packet.matchedRules.toSet()) {
      reject(label, "Assignment matched rules differ from the packet rules for '${assignment.lane}'.")
    }
    val unknownTargets = assignment.evidenceTargets.filterNot { it in packet.evidenceTargets }
    if (unknownTargets.isNotEmpty()) {
      reject(
        label,
        "Assignment evidence targets are not packet-owned: ${unknownTargets.map { it.targetId }.sorted()}.",
      )
    }
    val expectedTargets = packet.evidenceTargets.filter { it.path in expectedPaths }.toSet()
    if (assignment.evidenceTargets.toSet() != expectedTargets) {
      reject(label, "Assignment evidence targets differ from the packet targets for '${assignment.lane}'.")
    }
  }
}

private fun evidenceTargetsFor(hunks: List<ReviewChangedHunk>) = hunks
  .groupBy { it.path }
  .toSortedMap()
  .map { (path, grouped) -> ReviewEvidenceTarget(path, path, grouped.map { it.hunkId }.sorted()) }

private fun parentLabel(packet: ReviewContextPacket) = "review-packet:${packet.reviewId}"

private fun assignmentLabel(assignment: ReviewAssignment) =
  "review-assignment:${assignment.reviewId}:${assignment.lane}"

private fun rejectUnknownAssignmentDigests(
  label: String,
  expansions: List<ReviewExpansionRecord>,
  knownAssignmentDigests: Set<String>,
  subject: String,
) {
  val unknown = expansions.filterNot { it.assignmentDigest in knownAssignmentDigests }
  if (unknown.isEmpty()) return
  val described = unknown.sortedBy { it.expansionId }
    .joinToString(", ") { "${it.expansionId} -> ${it.assignmentDigest}" }
  reject(label, "$subject name assignment digests that belong to no assignment in this review: $described.")
}

private fun reject(sourceLabel: String, reason: String): Nothing =
  throw InvalidReviewContextSchemaError(sourceLabel = sourceLabel, reason = reason)
