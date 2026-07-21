package skillbill.review.context.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"

const val REVIEW_RULE_EXCERPT_MAX_CHARS: Int = 2_000

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }

private val SHA256_HEX = Regex("[a-f0-9]{64}")

data class ReviewRevision(val sessionId: String, val runRevision: Int) {
  init {
    require(sessionId.isNotBlank()) { "Review revision session id must not be blank." }
    require(runRevision >= 1) { "Review run revision must be positive." }
  }

  val canonical: String get() = "$sessionId\u001f$runRevision"
}

data class ReviewRuleReference(
  val ruleId: String,
  val sourcePath: String,
  val excerpt: String,
  val digest: String,
) {
  init {
    require(ruleId.isNotBlank()) { "Matched rule id must not be blank." }
    requireRepositoryRelativePath(sourcePath)
    require(excerpt.isNotBlank()) { "Matched rule excerpt must not be blank." }
    require(excerpt.length <= REVIEW_RULE_EXCERPT_MAX_CHARS) {
      "Matched rule excerpt exceeds the bounded projection limit of $REVIEW_RULE_EXCERPT_MAX_CHARS characters."
    }
    require(digest.matches(SHA256_HEX)) { "Matched rule digest must be lowercase SHA-256." }
  }

  val canonical: String
    get() = listOf(ruleId, sourcePath.replace('\\', '/'), excerpt.replace("\r\n", "\n"), digest)
      .joinToString("\u001f")
}

data class ReviewLearningsReference(val learningId: String, val source: String, val digest: String) {
  init {
    require(learningId.isNotBlank() && source.isNotBlank()) { "Learnings reference identity must not be blank." }
    require(digest.matches(SHA256_HEX)) { "Learnings reference digest must be lowercase SHA-256." }
  }

  val canonical: String get() = listOf(learningId, source, digest).joinToString("\u001f")
}

data class ReviewBuildTestFact(val kind: String, val command: String, val outcome: String) {
  init {
    require(kind.isNotBlank() && command.isNotBlank() && outcome.isNotBlank()) {
      "Build/test facts must carry a kind, command, and outcome."
    }
  }

  val canonical: String get() = listOf(kind, command, outcome).joinToString("\u001f")
}

data class ReviewDependencyAllowlist(val paths: List<String>) {
  init {
    paths.forEach(::requireRepositoryRelativePath)
    require(normalized.distinct().size == normalized.size) { "Dependency allowlist paths must be unique." }
  }

  val normalized: List<String> get() = paths.map { it.replace('\\', '/') }
  val canonical: String get() = normalized.sorted().joinToString("\u001f")

  companion object {
    val EMPTY: ReviewDependencyAllowlist = ReviewDependencyAllowlist(emptyList())
  }
}

data class ReviewEvidenceTarget(val targetId: String, val path: String, val hunkIds: List<String>) {
  init {
    require(targetId.isNotBlank()) { "Evidence target id must not be blank." }
    requireRepositoryRelativePath(path)
    require(hunkIds.distinct().size == hunkIds.size) { "Evidence target hunk ids must be unique." }
    require(hunkIds.all { it.matches(SHA256_HEX) }) { "Evidence target hunk ids must be content-addressed." }
  }

  val canonical: String
    get() = listOf(targetId, path.replace('\\', '/'), hunkIds.sorted().joinToString(",")).joinToString("\u001f")
}

data class ReviewLaneDecision(
  val lane: String,
  val included: Boolean,
  val reason: String,
  val signals: List<String> = emptyList(),
) {
  init {
    require(lane.isNotBlank()) { "Lane decision lane must not be blank." }
    require(reason.isNotBlank()) { "Lane decision '$lane' must carry a non-blank reason." }
    require(signals.distinct().size == signals.size) { "Lane decision signals must be unique." }
  }

  val canonical: String
    get() = listOf(lane, included.toString(), reason, signals.sorted().joinToString(","))
      .joinToString("\u001f")
}

data class ReviewExpansionRecord(
  val expansionId: String,
  val assignmentDigest: String,
  val requestedPath: String,
  val reachabilityReason: String,
  val authorized: Boolean,
  val sequence: Int,
) {
  init {
    require(expansionId.isNotBlank()) { "Expansion id must not be blank." }
    require(assignmentDigest.matches(SHA256_HEX)) { "Expansion assignment digest must be lowercase SHA-256." }
    requireRepositoryRelativePath(requestedPath)
    require(reachabilityReason.isNotBlank()) { "Expansion '$expansionId' must carry a reachability reason." }
    require(sequence >= 0) { "Expansion sequence cannot be negative." }
  }

  val canonical: String
    get() = listOf(
      expansionId,
      assignmentDigest,
      requestedPath.replace('\\', '/'),
      reachabilityReason,
      authorized.toString(),
      sequence.toString(),
    ).joinToString("\u001f")
}

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
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val reviewRevision: ReviewRevision = ReviewRevision(reviewId, 1),
  val laneDecision: ReviewLaneDecision = ReviewLaneDecision(lane, true, "Lane selected for delegated review."),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val expansions: List<ReviewExpansionRecord> = emptyList(),
) {
  init {
    require(reviewId.isNotBlank() && lane.isNotBlank() && baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(packetDigest.matches(SHA256_HEX)) { "Packet digest must be lowercase SHA-256." }
    require(assignedPaths.distinct().size == assignedPaths.size) { "Assigned paths must be unique." }
    assignedPaths.forEach(::requireRepositoryRelativePath)
    require(assignedHunks.distinct().size == assignedHunks.size) { "Assigned hunk ids must be unique." }
    require(laneDecision.lane == lane) { "Lane decision '${laneDecision.lane}' does not describe lane '$lane'." }
    require(laneDecision.included) { "Assignments exist only for included lanes; '$lane' is excluded." }
    require(matchedRules.map { it.ruleId }.distinct().size == matchedRules.size) { "Matched rules must be unique." }
    require(evidenceTargets.map { it.targetId }.distinct().size == evidenceTargets.size) {
      "Evidence target ids must be unique."
    }
    val assigned = assignedPaths.map { it.replace('\\', '/') }.toSet()
    require(dependencyAllowlist.normalized.none { it in assigned }) {
      "Dependency-allowlist entries must be disjoint from assigned paths."
    }
  }
  val digest: String
    get() = sha256(
      listOf(
        reviewId,
        packetDigest,
        reviewRevision.canonical,
        lane,
        laneDecision.canonical,
        baseRevision,
        headRevision,
        assignedPaths.map { it.replace('\\', '/') }.sorted().joinToString("\u001f"),
        assignedHunks.sorted().joinToString("\u001f"),
        criteriaReferences.sorted().joinToString("\u001f"),
        matchedRules.map { it.canonical }.sorted().joinToString("\u001f"),
        evidenceTargets.map { it.canonical }.sorted().joinToString("\u001f"),
        dependencyAllowlist.canonical,
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

  val hunkId: String get() = sha256(canonicalValue())

  internal fun canonicalValue(): String = listOf(
    path.replace('\\', '/'),
    oldStart,
    oldCount,
    newStart,
    newCount,
    content.replace("\r\n", "\n"),
  ).joinToString("\u001f")
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
  val reviewRevision: ReviewRevision = ReviewRevision(reviewId, 1),
  val laneDecisions: List<ReviewLaneDecision> =
    selectedLanes.map { ReviewLaneDecision(it, true, "Lane selected for delegated review.") },
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val learningsReferences: List<ReviewLearningsReference> = emptyList(),
  val buildTestFacts: List<ReviewBuildTestFact> = emptyList(),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val expansionLedger: List<ReviewExpansionRecord> = emptyList(),
) {
  init {
    require(reviewId.isNotBlank() && repositoryIdentity.isNotBlank())
    require(baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(selectedLanes.isNotEmpty() && selectedLanes.distinct().size == selectedLanes.size)
    require(addOns.distinct().size == addOns.size)
    require(changedHunks.map { it.path to listOf(it.oldStart, it.newStart) }.distinct().size == changedHunks.size)
    require(changedHunks.map { it.hunkId }.distinct().size == changedHunks.size) { "Changed hunk ids must be unique." }
    require(laneDecisions.map { it.lane }.distinct().size == laneDecisions.size) {
      "Lane decisions must carry one entry per lane."
    }
    require(laneDecisions.filter { it.included }.map { it.lane }.toSet() == selectedLanes.toSet()) {
      "Lane decisions must cover exactly the selected lanes."
    }
    require(matchedRules.map { it.ruleId }.distinct().size == matchedRules.size) { "Matched rules must be unique." }
    require(learningsReferences.map { it.learningId }.distinct().size == learningsReferences.size) {
      "Learnings references must be unique."
    }
    require(evidenceTargets.map { it.targetId }.distinct().size == evidenceTargets.size) {
      "Evidence target ids must be unique."
    }
    require(expansionLedger.map { it.expansionId }.distinct().size == expansionLedger.size) {
      "Expansion ledger ids must be unique."
    }
  }

  val ownedPaths: Set<String> get() = changedHunks.map { it.path.replace('\\', '/') }.toSet()
  val ownedHunkIds: Set<String> get() = changedHunks.map { it.hunkId }.toSet()

  val digest: String get() = sha256(canonicalValue())
  val canonicalBytes: Long get() = canonicalValue().toByteArray(StandardCharsets.UTF_8).size.toLong()

  private fun canonicalValue(): String = listOf(
    reviewId,
    reviewRevision.canonical,
    repositoryIdentity.replace('\\', '/'),
    baseRevision,
    headRevision,
    status.replace("\r\n", "\n"),
    stack.orEmpty(),
    pack.orEmpty(),
    addOns.sorted().joinToString("\u001f"),
    selectedLanes.sorted().joinToString("\u001f"),
    laneDecisions.map { it.canonical }.sorted().joinToString("\u001f"),
    changedHunks.sortedWith(compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart))
      .joinToString("\u001e") { it.canonicalValue() },
    matchedRules.map { it.canonical }.sorted().joinToString("\u001f"),
    learningsReferences.map { it.canonical }.sorted().joinToString("\u001f"),
    buildTestFacts.map { it.canonical }.sorted().joinToString("\u001f"),
    dependencyAllowlist.canonical,
    evidenceTargets.map { it.canonical }.sorted().joinToString("\u001f"),
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
    appendLine("review_revision: ${assignment.reviewRevision.sessionId}@${assignment.reviewRevision.runRevision}")
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
    assignment.matchedRules.map { it.ruleId }.sorted().forEach { appendLine("  - $it") }
    appendLine("evidence_targets:")
    assignment.evidenceTargets.map { it.targetId }.sorted().forEach { appendLine("  - $it") }
    appendLine("dependency_allowlist:")
    assignment.dependencyAllowlist.normalized.sorted().forEach { appendLine("  - $it") }
    appendLine("forbidden_rediscovery:")
    ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.forEach { appendLine("  - $it") }
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
