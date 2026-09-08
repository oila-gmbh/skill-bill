package skillbill.review.context.model

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"
const val REVIEW_ROUTING_ANALYSIS_PAIRS_BUDGET: String = "routing_analysis_pairs"

const val REVIEW_ROUTING_ANALYSIS_BYTES_BUDGET: String = "routing_analysis_bytes"

const val REVIEW_SPEC_INTENT_PROJECTION_BUDGET: String = "spec_intent_projection"
data class ReviewContextBudgetPolicy(
  val maxParentPacketBytes: Long = 524_288,
  val maxLaneLaunchBytes: Long = 65_536,
  val maxLaneEvidenceBytes: Long = 262_144,
  val maxEvidenceResultBytes: Long = 65_536,
  val maxLaneResultBytes: Long = 65_536,
  val maxAssignmentExpansions: Int = 3,
  val maxSpecialistToolCalls: Int = 40,
  val maxSpecialistModelTurns: Int = 24,
  val maxRoutingAnalysisPairs: Int = 4_096,
  val maxRoutingAnalysisBytes: Long = 33_554_432,
  val maxSpecIntentProjectionBytes: Long = 32_768,
) {
  init {
    val byteLimits = listOf(
      maxParentPacketBytes,
      maxLaneLaunchBytes,
      maxLaneEvidenceBytes,
      maxEvidenceResultBytes,
      maxLaneResultBytes,
      maxSpecIntentProjectionBytes,
    )
    require(byteLimits.all { it > 0 }) { "Review-context byte limits must be positive." }
    require(maxAssignmentExpansions >= 0) { "Assignment expansions cannot be negative." }
    require(maxSpecialistToolCalls > 0) { "Specialist tool-call budget must be positive." }
    require(maxSpecialistModelTurns > 0) { "Specialist model-turn budget must be positive." }
    require(maxRoutingAnalysisPairs > 0) { "Routing-analysis commit/lane pair budget must be positive." }
    require(maxRoutingAnalysisBytes > 0) { "Routing-analysis hunk-material byte budget must be positive." }
    require(maxEvidenceResultBytes <= maxLaneEvidenceBytes) {
      "Evidence-result bytes cannot exceed cumulative lane-evidence bytes."
    }
    require(maxLaneLaunchBytes <= maxParentPacketBytes) { "Lane-launch bytes cannot exceed parent-packet bytes." }
  }

  companion object {
    val DEFAULT: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy()

    /**
     * [maxLaneEvidenceBytes] is the cumulative broker `read_evidence` allowance for this lane's
     * assigned surface, scaled from [basePolicy] by the lane's share of packet hunk content bytes,
     * not a flat cap independent of assignment breadth.
     */
    fun deriveLaneEvidenceBytes(
      basePolicy: ReviewContextBudgetPolicy,
      assignment: ReviewAssignment,
      packet: ReviewContextPacket,
    ): Long {
      val packetBytes = packet.changedHunks.sumOf { it.contentBytes }
      if (packetBytes == 0L) return basePolicy.maxLaneEvidenceBytes
      val bytesByHunkId = packet.changedHunks.associate { it.hunkId to it.contentBytes }
      val assignmentBytes = assignment.assignedHunks.sumOf { bytesByHunkId.getValue(it) }
      val scaled = basePolicy.maxLaneEvidenceBytes * assignmentBytes / packetBytes
      return maxOf(scaled, basePolicy.maxEvidenceResultBytes)
    }
  }
}
sealed interface ReviewBudgetOutcome {
  val lane: String
  val budgetKind: String
  val configuredLimit: Long
  val observedValue: Long
  val packetDigest: String
  val assignmentDigest: String
  val enforceable: Boolean
  val type: String
}

/** Loud failure used when preparation reaches an enforceable budget boundary before a worker starts. */
class ReviewContextBudgetExceededException(
  val outcome: ReviewContextBudgetExceeded,
) : RuntimeException(
  "${outcome.type}: ${outcome.budgetKind} ${outcome.observedValue} > ${outcome.configuredLimit}",
)

/**
 * Loud failure raised when the lane register parse seam throws. It names the seam and the lane and
 * bounds the cause detail, so a parser throw-site that echoes the lane output into its own message
 * still cannot turn this message into a lane body dump; the untruncated cause stays reachable
 * through [cause].
 */
class ReviewRegisterParseSeamException(
  val seam: String,
  val lane: String,
  cause: Throwable,
) : RuntimeException(
  "Review register parse seam '$seam' failed for lane '$lane': " +
    "${cause::class.simpleName}: ${cause.message?.take(CAUSE_DETAIL_MAX_LENGTH) ?: "no detail"}",
  cause,
) {
  init {
    require(seam.isNotBlank() && lane.isNotBlank()) {
      "Review register parse seam failure must name its seam and lane."
    }
  }

  companion object {
    const val CAUSE_DETAIL_MAX_LENGTH: Int = 200
  }
}

data class ReviewContextBudgetExceeded(
  override val lane: String,
  override val budgetKind: String,
  override val configuredLimit: Long,
  override val observedValue: Long,
  override val packetDigest: String,
  override val assignmentDigest: String,
  override val enforceable: Boolean,
) : ReviewBudgetOutcome {
  override val type: String = REVIEW_CONTEXT_BUDGET_EXCEEDED
  init {
    require(lane.isNotBlank() && budgetKind.isNotBlank())
    require(configuredLimit >= 0 && observedValue > configuredLimit)
  }
}

data class ReviewLaneIdentity(val lane: String, val packetDigest: String, val assignmentDigest: String) {
  init {
    require(lane.isNotBlank()) { "Review lane identity lane must not be blank." }
    require(packetDigest.matches(SHA256_HEX) && assignmentDigest.matches(SHA256_HEX)) {
      "Review lane identity digests must be lowercase SHA-256."
    }
  }

  companion object {
    fun of(assignment: ReviewAssignment): ReviewLaneIdentity =
      ReviewLaneIdentity(assignment.lane, assignment.packetDigest, assignment.digest)

    /**
     * The dual-agent parallel runner has no packet; its lane identity is content-addressed over the
     * authoritative parent prompt it hands each agent, which is the only scope artifact that exists there.
     */
    fun ofParallelLane(agentId: String, parentPrompt: String): ReviewLaneIdentity = ReviewLaneIdentity(
      lane = agentId,
      packetDigest = sha256(parentPrompt.replace("\r\n", "\n")),
      assignmentDigest = sha256(agentId + "\u001f" + parentPrompt.replace("\r\n", "\n")),
    )
  }
}

object ReviewBudgetEvaluator {
  fun laneResultOutcome(
    identity: ReviewLaneIdentity,
    budget: ReviewContextBudgetPolicy,
    observedBytes: Long,
  ): ReviewContextBudgetExceeded? = exceededOrNull(
    identity,
    "lane_result_bytes",
    budget.maxLaneResultBytes,
    observedBytes,
  )

  fun exceededOrNull(
    identity: ReviewLaneIdentity,
    budgetKind: String,
    configuredLimit: Long,
    observedValue: Long,
  ): ReviewContextBudgetExceeded? = if (observedValue > configuredLimit) {
    ReviewContextBudgetExceeded(
      lane = identity.lane,
      budgetKind = budgetKind,
      configuredLimit = configuredLimit,
      observedValue = observedValue,
      packetDigest = identity.packetDigest,
      assignmentDigest = identity.assignmentDigest,
      enforceable = true,
    )
  } else {
    null
  }
}
