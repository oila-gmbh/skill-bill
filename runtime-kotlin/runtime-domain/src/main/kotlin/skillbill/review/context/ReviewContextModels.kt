@file:Suppress("MaxLineLength")

package skillbill.review.context

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"

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
    require(totalTokens >= dimensions.dropLast(1).max()) { "Total-token threshold must be at least every individual threshold." }
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
    require(listOf(maxParentPacketBytes, maxLaneLaunchBytes, maxLaneEvidenceBytes, maxEvidenceResultBytes, maxLaneResultBytes).all { it > 0 }) { "Review-context byte limits must be positive." }
    require(maxAssignmentExpansions >= 0) { "Assignment expansions cannot be negative." }
    require(maxEvidenceResultBytes <= maxLaneEvidenceBytes) { "Evidence-result bytes cannot exceed cumulative lane-evidence bytes." }
    require(maxLaneLaunchBytes <= maxParentPacketBytes) { "Lane-launch bytes cannot exceed parent-packet bytes." }
  }

  companion object { val DEFAULT: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy() }
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
  init { require(listOf(inputTokens, cachedInputTokens, outputTokens, reasoningTokens, totalTokens).all { it == null || it >= 0 }) }
  val freshTokenApproximation: Long?
    get() = if (inputTokens == null && outputTokens == null) null else ((inputTokens ?: 0) - (cachedInputTokens ?: 0)).coerceAtLeast(0) + (outputTokens ?: 0)
}

data class ReviewTreeUsage(val node: ProviderTokenUsage, val children: List<ReviewTreeUsage> = emptyList()) {
  fun aggregate(): ProviderTokenUsage = if (node.ownership == TokenOwnership.INCLUSIVE) node else children.fold(node) { total, child -> total.plus(child.aggregate()) }
}

private fun ProviderTokenUsage.plus(other: ProviderTokenUsage): ProviderTokenUsage = ProviderTokenUsage(
  inputTokens = inputTokens.add(other.inputTokens), cachedInputTokens = cachedInputTokens.add(other.cachedInputTokens),
  outputTokens = outputTokens.add(other.outputTokens), reasoningTokens = reasoningTokens.add(other.reasoningTokens), totalTokens = totalTokens.add(other.totalTokens),
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
  val digest: String get() = sha256(listOf(reviewId, packetDigest, lane, baseRevision, headRevision, assignedPaths.sorted().joinToString("\u001f"), assignedHunks.sorted().joinToString("\u001f"), criteriaReferences.sorted().joinToString("\u001f"), matchedRules.sorted().joinToString("\u001f"), evidenceTargets.sorted().joinToString("\u001f")).joinToString("\n").replace("\r\n", "\n"))
}

data class ReviewContextBudgetExceeded(
  val lane: String, val budgetKind: String, val configuredLimit: Long, val observedValue: Long,
  val packetDigest: String, val assignmentDigest: String, val enforceable: Boolean,
) {
  val type: String = REVIEW_CONTEXT_BUDGET_EXCEEDED
  init { require(lane.isNotBlank() && budgetKind.isNotBlank()); require(configuredLimit >= 0 && observedValue > configuredLimit) }
}

fun requireRepositoryRelativePath(path: String) {
  val normalized = path.replace('\\', '/')
  require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Review paths must be repository-relative." }
  require(normalized.split('/').none { it == ".." }) { "Review paths cannot traverse outside the repository." }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
