package skillbill.review.context.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }

data class ReviewAutoEligibility(val oversized: Boolean, val highRisk: Boolean, val layeredStack: Boolean)

data class ProviderTokenThresholds(
  val inputTokens: Long = 64_000,
  val cachedInputTokens: Long = 48_000,
  val outputTokens: Long = 12_000,
  val reasoningTokens: Long = 16_000,
  val totalTokens: Long = 96_000,
) {
  init {
    val dimensions = listOf(inputTokens, cachedInputTokens, outputTokens, reasoningTokens, totalTokens)
    require(dimensions.all { it > 0 }) { "Provider token thresholds must be positive." }
    require(totalTokens >= dimensions.dropLast(1).max()) {
      "Total-token threshold must be at least every individual threshold."
    }
  }
}

data class ReviewContextBudgetPolicy(
  val maxParentPacketBytes: Long = 524_288,
  val maxLaneLaunchBytes: Long = 65_536,
  val maxLaneEvidenceBytes: Long = 262_144,
  val maxEvidenceResultBytes: Long = 65_536,
  val maxLaneResultBytes: Long = 65_536,
  val maxAssignmentExpansions: Int = 3,
  val providerTokenThresholds: ProviderTokenThresholds = ProviderTokenThresholds(),
) {
  init {
    val byteLimits = listOf(
      maxParentPacketBytes,
      maxLaneLaunchBytes,
      maxLaneEvidenceBytes,
      maxEvidenceResultBytes,
      maxLaneResultBytes,
    )
    require(byteLimits.all { it > 0 }) { "Review-context byte limits must be positive." }
    require(maxAssignmentExpansions >= 0) { "Assignment expansions cannot be negative." }
    require(maxEvidenceResultBytes <= maxLaneEvidenceBytes) {
      "Evidence-result bytes cannot exceed cumulative lane-evidence bytes."
    }
    require(maxLaneLaunchBytes <= maxParentPacketBytes) { "Lane-launch bytes cannot exceed parent-packet bytes." }
  }

  companion object {
    val DEFAULT: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy()
  }
}

enum class TokenOwnership { DIRECT, INCLUSIVE }

data class ProviderTokenUsage(
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningTokens: Long? = null,
  val totalTokens: Long? = null,
  val ownership: TokenOwnership = TokenOwnership.DIRECT,
) {
  init {
    require(
      listOf(inputTokens, cachedInputTokens, outputTokens, reasoningTokens, totalTokens).all {
        it == null || it >= 0
      },
    )
    require(cachedInputTokens == null || inputTokens != null) { "Cached-input tokens require input tokens." }
    require(cachedInputTokens == null || cachedInputTokens <= inputTokens!!) {
      "Cached-input tokens cannot exceed input tokens."
    }
  }
  val freshTokenApproximation: Long?
    get() = if (inputTokens == null && outputTokens == null) {
      null
    } else {
      (inputTokens ?: 0) - (cachedInputTokens ?: 0) + (outputTokens ?: 0)
    }
}

data class ReviewTreeUsage(val node: ProviderTokenUsage, val children: List<ReviewTreeUsage> = emptyList()) {
  fun aggregate(): ProviderTokenUsage = if (node.ownership == TokenOwnership.INCLUSIVE) {
    node
  } else {
    children.fold(node) { total, child -> total.plus(child.aggregate()) }
  }
}

private fun ProviderTokenUsage.plus(other: ProviderTokenUsage): ProviderTokenUsage = ProviderTokenUsage(
  inputTokens = inputTokens.add(other.inputTokens),
  cachedInputTokens = cachedInputTokens.add(other.cachedInputTokens),
  outputTokens = outputTokens.add(other.outputTokens),
  reasoningTokens = reasoningTokens.add(other.reasoningTokens),
  totalTokens = totalTokens.add(other.totalTokens),
)
private fun Long?.add(other: Long?): Long? = if (this == null && other == null) null else (this ?: 0) + (other ?: 0)

data class ReviewAssignment(
  val reviewId: String,
  val packetDigest: String,
  val lane: String,
  val baseRevision: String,
  val headRevision: String,
  val assignedPaths: List<String>,
  val assignedHunks: List<String>,
  val criteriaReferences: List<String> = emptyList(),
  val matchedRules: List<String> = emptyList(),
  val evidenceTargets: List<String> = emptyList(),
) {
  init {
    require(reviewId.isNotBlank() && lane.isNotBlank() && baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(packetDigest.matches(Regex("[a-f0-9]{64}"))) { "Packet digest must be lowercase SHA-256." }
    require(assignedPaths.distinct().size == assignedPaths.size) { "Assigned paths must be unique." }
    assignedPaths.forEach(::requireRepositoryRelativePath)
  }
  val digest: String
    get() = sha256(
      listOf(
        reviewId,
        packetDigest,
        lane,
        baseRevision,
        headRevision,
        assignedPaths.sorted().joinToString("\u001f"),
        assignedHunks.sorted().joinToString("\u001f"),
        criteriaReferences.sorted().joinToString("\u001f"),
        matchedRules.sorted().joinToString("\u001f"),
        evidenceTargets.sorted().joinToString("\u001f"),
      ).joinToString("\n").replace("\r\n", "\n"),
    )
}

data class ReviewChangedHunk(
  val path: String,
  val oldStart: Int,
  val oldCount: Int,
  val newStart: Int,
  val newCount: Int,
  val content: String,
) {
  init {
    requireRepositoryRelativePath(path)
    require(oldStart >= 0 && oldCount >= 0 && newStart >= 0 && newCount >= 0)
  }
}

data class ReviewContextPacket(
  val reviewId: String,
  val repositoryIdentity: String,
  val baseRevision: String,
  val headRevision: String,
  val status: String,
  val stack: String?,
  val pack: String?,
  val addOns: List<String>,
  val selectedLanes: List<String>,
  val changedHunks: List<ReviewChangedHunk>,
) {
  init {
    require(reviewId.isNotBlank() && repositoryIdentity.isNotBlank())
    require(baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(selectedLanes.isNotEmpty() && selectedLanes.distinct().size == selectedLanes.size)
    require(addOns.distinct().size == addOns.size)
    require(changedHunks.map { it.path to listOf(it.oldStart, it.newStart) }.distinct().size == changedHunks.size)
  }

  val digest: String get() = sha256(canonicalValue())
  val canonicalBytes: Long get() = canonicalValue().toByteArray(StandardCharsets.UTF_8).size.toLong()

  private fun canonicalValue(): String = listOf(
    reviewId,
    repositoryIdentity.replace('\\', '/'),
    baseRevision,
    headRevision,
    status.replace("\r\n", "\n"),
    stack.orEmpty(),
    pack.orEmpty(),
    addOns.sorted().joinToString("\u001f"),
    selectedLanes.sorted().joinToString("\u001f"),
    changedHunks.sortedWith(compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart)).joinToString("\u001e") {
      listOf(
        it.path.replace('\\', '/'),
        it.oldStart,
        it.oldCount,
        it.newStart,
        it.newCount,
        it.content.replace("\r\n", "\n"),
      ).joinToString("\u001f")
    },
  ).joinToString("\n")
}

enum class ReviewConversationIsolation { FRESH }

data class GovernedReviewLaunch(
  val assignment: ReviewAssignment,
  val specialistContract: String,
  val rubric: String,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(specialistContract.isNotBlank() && rubric.isNotBlank() && brokerId.isNotBlank())
  }

  fun requireCodexForkTurns(forkTurns: String?) {
    require(forkTurns == "none") { "Governed Codex review launches require fork_turns none." }
  }

  val canonicalPayload: String get() = buildString {
    appendLine("review_id: ${assignment.reviewId}")
    appendLine("packet_digest: ${assignment.packetDigest}")
    appendLine("assignment_digest: ${assignment.digest}")
    appendLine("lane: ${assignment.lane}")
    appendLine("base_revision: ${assignment.baseRevision}")
    appendLine("head_revision: ${assignment.headRevision}")
    appendLine("broker_id: $brokerId")
    appendLine("specialist_contract: |")
    specialistContract.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    appendLine("rubric: |")
    rubric.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    appendLine("assigned_paths:")
    assignment.assignedPaths.sorted().forEach { appendLine("  - ${it.replace('\\', '/')}") }
    appendLine("assigned_hunks:")
    assignment.assignedHunks.sorted().forEach { appendLine("  - $it") }
    appendLine("criteria_references:")
    assignment.criteriaReferences.sorted().forEach { appendLine("  - $it") }
    appendLine("matched_rules:")
    assignment.matchedRules.sorted().forEach { appendLine("  - $it") }
    appendLine("evidence_targets:")
    assignment.evidenceTargets.sorted().forEach { appendLine("  - $it") }
    appendLine(
      "budgets: launch=${budget.maxLaneLaunchBytes}, evidence=${budget.maxLaneEvidenceBytes}, " +
        "result=${budget.maxLaneResultBytes}, expansions=${budget.maxAssignmentExpansions}",
    )
  }.trimEnd()

  fun budgetOutcomeOrNull(): ReviewContextBudgetExceeded? {
    val observed = canonicalPayload.toByteArray(StandardCharsets.UTF_8).size.toLong()
    return if (observed > budget.maxLaneLaunchBytes) {
      ReviewContextBudgetExceeded(
        lane = assignment.lane,
        budgetKind = "lane_launch_bytes",
        configuredLimit = budget.maxLaneLaunchBytes,
        observedValue = observed,
        packetDigest = assignment.packetDigest,
        assignmentDigest = assignment.digest,
        enforceable = true,
      )
    } else {
      null
    }
  }
}

data class ReviewContextBudgetExceeded(
  val lane: String,
  val budgetKind: String,
  val configuredLimit: Long,
  val observedValue: Long,
  val packetDigest: String,
  val assignmentDigest: String,
  val enforceable: Boolean,
) {
  val type: String = REVIEW_CONTEXT_BUDGET_EXCEEDED
  init {
    require(lane.isNotBlank() && budgetKind.isNotBlank())
    require(configuredLimit >= 0 && observedValue > configuredLimit)
  }
}

fun requireRepositoryRelativePath(path: String) {
  val normalized = path.replace('\\', '/')
  require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Review paths must be repository-relative." }
  require(normalized.split('/').none { it == ".." }) { "Review paths cannot traverse outside the repository." }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(StandardCharsets.UTF_8))
  .joinToString("") { byte -> "%02x".format(byte) }
