@file:Suppress("SpreadOperator", "MagicNumber")

package skillbill.review.context.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"

const val REVIEW_BUDGET_REGRESSION: String = "budget_regression"

const val REVIEW_RULE_EXCERPT_MAX_CHARS: Int = 2_000

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }

private val SHA256_HEX = Regex("[a-f0-9]{64}")

data class ReviewRevision(val sessionId: String, val runRevision: Int) {
  init {
    require(sessionId.isNotBlank()) { "Review revision session id must not be blank." }
    require(runRevision >= 1) { "Review run revision must be positive." }
  }

  val canonical: String get() = canonicalFields(sessionId, runRevision)
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
    require(digest == digestOf(excerpt)) {
      "Matched rule '$ruleId' digest does not cover its excerpt; the excerpt is not attested."
    }
  }

  companion object {
    fun digestOf(excerpt: String): String = sha256(excerpt.replace("\r\n", "\n"))
  }

  val canonical: String
    get() = listOf(ruleId, sourcePath, excerpt.replace("\r\n", "\n"), digest)
      .let { canonicalFields(*it.toTypedArray()) }
}

data class ReviewLearningsReference(val learningId: String, val source: String, val digest: String) {
  init {
    require(learningId.isNotBlank() && source.isNotBlank()) { "Learnings reference identity must not be blank." }
    require(digest.matches(SHA256_HEX)) { "Learnings reference digest must be lowercase SHA-256." }
  }

  val canonical: String get() = canonicalFields(learningId, source, digest)
}

data class ReviewBuildTestFact(val kind: String, val command: String, val outcome: String) {
  init {
    require(kind.isNotBlank() && command.isNotBlank() && outcome.isNotBlank()) {
      "Build/test facts must carry a kind, command, and outcome."
    }
  }

  val canonical: String get() = canonicalFields(kind, command, outcome)
}

data class ReviewDependencyAllowlist(val paths: List<String>) {
  init {
    paths.forEach(::requireRepositoryRelativePath)
    require(normalized.distinct().size == normalized.size) { "Dependency allowlist paths must be unique." }
  }

  val normalized: List<String> get() = paths
  val canonical: String get() = canonicalFields(*normalized.sorted().toTypedArray())

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
    get() = canonicalFields(targetId, path, canonicalFields(*hunkIds.sorted().toTypedArray()))
}

data class ReviewLaneDecision(
  val lane: String,
  val included: Boolean,
  val reason: String,
  val signals: List<String> = emptyList(),
  val ownedPaths: List<String> = emptyList(),
  val orderIndex: Int = 0,
  val required: Boolean = false,
  val originLayerChains: List<List<String>> = emptyList(),
  val owningPack: String? = null,
  val specialistSkillName: String? = null,
  val addOns: List<String> = emptyList(),
) {
  init {
    require(lane.isNotBlank()) { "Lane decision lane must not be blank." }
    require(reason.isNotBlank()) { "Lane decision '$lane' must carry a non-blank reason." }
    require(signals.distinct().size == signals.size) { "Lane decision signals must be unique." }
    ownedPaths.forEach(::requireRepositoryRelativePath)
    require(normalizedOwnedPaths.distinct().size == ownedPaths.size) {
      "Lane decision '$lane' owned paths must be unique."
    }
    require(!included || ownedPaths.isNotEmpty()) {
      "Included lane '$lane' must declare the paths it owns so assignments partition the packet."
    }
    require(included || ownedPaths.isEmpty()) { "Excluded lane '$lane' cannot own paths." }
    require(orderIndex >= 0) { "Lane decision order index cannot be negative." }
    require(originLayerChains.all { it.isNotEmpty() && it.all(String::isNotBlank) }) {
      "Lane decision origin chains must contain non-blank pack slugs."
    }
    require(originLayerChains.distinct().size == originLayerChains.size) {
      "Lane decision origin chains must be unique."
    }
    require(addOns.distinct().size == addOns.size) { "Lane decision add-ons must be unique." }
    if (included) {
      require(originLayerChains.isNotEmpty()) { "Included lane '$lane' must declare an origin chain." }
      require(!owningPack.isNullOrBlank()) { "Included lane '$lane' must declare its owning pack." }
      require(!specialistSkillName.isNullOrBlank()) { "Included lane '$lane' must declare its specialist skill." }
    }
  }

  val normalizedOwnedPaths: List<String> get() = ownedPaths

  val canonical: String
    get() = listOf(
      lane,
      included.toString(),
      reason,
      canonicalFields(*signals.sorted().toTypedArray()),
      canonicalFields(*normalizedOwnedPaths.sorted().toTypedArray()),
      orderIndex.toString(),
      required.toString(),
      canonicalFields(*originLayerChains.map { canonicalFields(*it.toTypedArray()) }.toTypedArray()),
      owningPack.orEmpty(),
      specialistSkillName.orEmpty(),
      canonicalFields(*addOns.toTypedArray()),
    )
      .let { canonicalFields(*it.toTypedArray()) }
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
      requestedPath,
      reachabilityReason,
      authorized.toString(),
      sequence.toString(),
    ).let { canonicalFields(*it.toTypedArray()) }
}

data class ReviewAutoEligibility(val oversized: Boolean, val highRisk: Boolean, val layeredStack: Boolean)

data class ProviderTokenThresholds(
  val inputTokens: Long = 40_000,
  val cachedInputTokens: Long = 30_000,
  val outputTokens: Long = 8_000,
  val reasoningTokens: Long = 10_000,
  val totalTokens: Long = 56_000,
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
  val maxSpecialistToolCalls: Int = 40,
  val maxSpecialistModelTurns: Int = 24,
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
    require(maxSpecialistToolCalls > 0) { "Specialist tool-call budget must be positive." }
    require(maxSpecialistModelTurns > 0) { "Specialist model-turn budget must be positive." }
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
  val reviewRevision: ReviewRevision,
  val laneDecision: ReviewLaneDecision,
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
    val assigned = assignedPaths.toSet()
    require(dependencyAllowlist.normalized.none { it in assigned }) {
      "Dependency-allowlist entries must be disjoint from assigned paths."
    }
    require(expansions.map { it.expansionId }.distinct().size == expansions.size) {
      "Assignment expansion ids must be unique."
    }
    require(expansions.map { it.sequence }.distinct().size == expansions.size) {
      "Assignment expansion sequences must be unique."
    }
    require(expansions.all { it.assignmentDigest == digest }) {
      "Assignment '$lane' expansions must reference their enclosing assignment digest."
    }
    val reachable = assigned + dependencyAllowlist.normalized
    val escaping = expansions.map { it.requestedPath }.filterNot { it in reachable }
    require(escaping.isEmpty()) {
      "Assignment '$lane' expansions authorize paths outside its allowlist and assigned paths: ${escaping.sorted()}."
    }
  }

  val expansionsDigest: String
    get() = sha256(
      expansions.sortedWith(compareBy({ it.sequence }, { it.expansionId })).joinToString("\n") { it.canonical },
    )
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
        canonicalFields(*assignedPaths.sorted().toTypedArray()),
        canonicalFields(*assignedHunks.sorted().toTypedArray()),
        canonicalFields(*criteriaReferences.sorted().toTypedArray()),
        canonicalFields(*matchedRules.map { it.canonical }.sorted().toTypedArray()),
        canonicalFields(*evidenceTargets.map { it.canonical }.sorted().toTypedArray()),
        dependencyAllowlist.canonical,
      ).let { canonicalFields(*it.toTypedArray()) }.replace("\r\n", "\n"),
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

  val hunkId: String by lazy(LazyThreadSafetyMode.PUBLICATION) { sha256(canonicalValue()) }

  internal fun canonicalValue(): String = canonicalFields(
    path, oldStart, oldCount, newStart, newCount, content.replace("\r\n", "\n"),
  )
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
  val reviewRevision: ReviewRevision,
  val laneDecisions: List<ReviewLaneDecision>,
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val learningsReferences: List<ReviewLearningsReference> = emptyList(),
  val buildTestFacts: List<ReviewBuildTestFact> = emptyList(),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val expansionLedger: List<ReviewExpansionRecord> = emptyList(),
  val composedLayers: List<String> = emptyList(),
) {
  init {
    require(reviewId.isNotBlank() && repositoryIdentity.isNotBlank())
    require(baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(selectedLanes.isNotEmpty() && selectedLanes.distinct().size == selectedLanes.size)
    require(addOns.distinct().size == addOns.size)
    require(composedLayers.all(String::isNotBlank) && composedLayers.distinct().size == composedLayers.size)
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
    require(expansionLedger.map { it.sequence }.distinct().size == expansionLedger.size) {
      "Expansion ledger sequences must be unique."
    }
    val owned = changedHunks.map { it.path }.toSet()
    val ownedHunks = changedHunks.map { it.hunkId }.toSet()
    val reachable = owned + dependencyAllowlist.normalized
    val escaping = expansionLedger.map { it.requestedPath }.filterNot { it in reachable }
    require(escaping.isEmpty()) {
      "Expansion ledger authorizes paths outside the packet allowlist and owned paths: ${escaping.sorted()}."
    }
    val targetPaths = evidenceTargets.map { it.path }.filterNot { it in owned }
    require(targetPaths.isEmpty()) { "Evidence targets name paths the packet does not own: ${targetPaths.sorted()}." }
    val targetHunks = evidenceTargets.flatMap { it.hunkIds }.filterNot { it in ownedHunks }
    require(targetHunks.isEmpty()) { "Evidence targets name hunk ids the packet does not own." }
  }

  val ownedPaths: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    changedHunks.map { it.path }.toSet()
  }
  val ownedHunkIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    changedHunks.map { it.hunkId }.toSet()
  }

  val digest: String get() = sha256(canonicalValue())

  val expansionLedgerDigest: String
    get() = sha256(
      expansionLedger.sortedWith(compareBy({ it.sequence }, { it.expansionId }))
        .joinToString("\n") { it.canonical },
    )

  val canonicalBytes: Long
    get() = (canonicalValue() + expansionLedgerDigest + expansionLedger.joinToString("\n") { it.canonical })
      .toByteArray(StandardCharsets.UTF_8).size.toLong()

  private fun canonicalValue(): String = listOf(
    reviewId,
    reviewRevision.canonical,
    repositoryIdentity,
    baseRevision,
    headRevision,
    status.replace("\r\n", "\n"),
    stack.orEmpty(),
    pack.orEmpty(),
    canonicalFields(*addOns.sorted().toTypedArray()),
    canonicalFields(*composedLayers.toTypedArray()),
    canonicalFields(*selectedLanes.toTypedArray()),
    laneDecisions.sortedWith(compareBy(ReviewLaneDecision::orderIndex, ReviewLaneDecision::lane))
      .map { it.canonical }.let { canonicalFields(*it.toTypedArray()) },
    changedHunks.sortedBy { it.canonicalValue() }
      .map { it.canonicalValue() }.let { canonicalFields(*it.toTypedArray()) },
    canonicalFields(*matchedRules.map { it.canonical }.sorted().toTypedArray()),
    canonicalFields(*learningsReferences.map { it.canonical }.sorted().toTypedArray()),
    canonicalFields(*buildTestFacts.map { it.canonical }.sorted().toTypedArray()),
    dependencyAllowlist.canonical,
    canonicalFields(*evidenceTargets.map { it.canonical }.sorted().toTypedArray()),
  ).let { canonicalFields(*it.toTypedArray()) }
}

enum class ReviewConversationIsolation { FRESH }

data class GovernedReviewLaunch(
  val assignment: ReviewAssignment,
  val packet: ReviewContextPacket,
  val specialistContract: String,
  val rubric: String,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(specialistContract.isNotBlank() && rubric.isNotBlank() && brokerId.isNotBlank())
    require(assignment.reviewId == packet.reviewId) { "Launch assignment belongs to a different review." }
    require(assignment.packetDigest == packet.digest) {
      "Launch assignment carries packet digest '${assignment.packetDigest}' but the packet recomputes to " +
        "'${packet.digest}'; a launch cannot be projected from an unattested assignment."
    }
    require(assignment.lane in packet.selectedLanes) {
      "Launch lane '${assignment.lane}' is not a selected lane of the packet."
    }
    require(assignment.reviewRevision == packet.reviewRevision) { "Launch review revision differs from the packet." }
    require(assignment.baseRevision == packet.baseRevision && assignment.headRevision == packet.headRevision) {
      "Launch base/head revisions differ from the packet."
    }
    val packetDecision = packet.laneDecisions.single { it.lane == assignment.lane }
    require(assignment.laneDecision == packetDecision) { "Launch lane decision differs from the packet." }
    val normalizedAssignedPaths = assignment.assignedPaths.toSet()
    val unowned = normalizedAssignedPaths.filterNot { it in packet.ownedPaths }
    require(unowned.isEmpty()) { "Launch claims paths the packet does not own: ${unowned.sorted()}." }
    require(normalizedAssignedPaths == packetDecision.normalizedOwnedPaths.toSet()) {
      "Launch paths differ from the packet lane decision."
    }
    val unownedHunks = assignment.assignedHunks.filterNot { it in packet.ownedHunkIds }
    require(unownedHunks.isEmpty()) { "Launch claims hunk ids the packet does not own." }
    val expectedHunks = packet.changedHunks
      .filter { it.path in packetDecision.normalizedOwnedPaths }
      .map { it.hunkId }
      .toSet()
    require(assignment.assignedHunks.toSet() == expectedHunks) {
      "Launch hunks differ from the packet-owned hunks for the lane."
    }
    require(assignment.dependencyAllowlist.normalized.all { it in packet.dependencyAllowlist.normalized }) {
      "Launch dependency allowlist escapes the packet allowlist."
    }
    require(assignment.matchedRules.toSet() == packet.matchedRules.toSet()) {
      "Launch matched rules differ from the packet rules."
    }
    val expectedTargets = packet.evidenceTargets
      .filter { it.path in packetDecision.normalizedOwnedPaths }
      .toSet()
    require(assignment.evidenceTargets.toSet() == expectedTargets) {
      "Launch evidence targets differ from the packet targets for the lane."
    }
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
    assignment.assignedPaths.sorted().forEach { appendLine("  - ${structuredString(it)}") }
    appendLine("assigned_hunks:")
    assignment.assignedHunks.sorted().forEach { appendLine("  - $it") }
    appendLine("immutable_diff_hunks:")
    packet.changedHunks.filter { it.hunkId in assignment.assignedHunks }
      .sortedWith(compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart))
      .forEach { hunk ->
        appendLine("  - path: ${structuredString(hunk.path)}")
        appendLine("    hunk_id: ${hunk.hunkId}")
        appendLine("    content: |")
        hunk.content.replace("\r\n", "\n").lineSequence().forEach { appendLine("      $it") }
      }
    appendLine("criteria_references:")
    assignment.criteriaReferences.sorted().forEach { appendLine("  - $it") }
    appendLine("matched_rules:")
    assignment.matchedRules.map { it.ruleId }.sorted().forEach { appendLine("  - $it") }
    appendLine("evidence_targets:")
    assignment.evidenceTargets.map { it.targetId }.sorted().forEach { appendLine("  - $it") }
    appendLine("dependency_allowlist:")
    assignment.dependencyAllowlist.normalized.sorted().forEach { appendLine("  - ${structuredString(it)}") }
    appendLine("forbidden_rediscovery:")
    ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.forEach { appendLine("  - $it") }
    appendLine(
      "budgets: launch=${budget.maxLaneLaunchBytes}, evidence=${budget.maxLaneEvidenceBytes}, " +
        "result=${budget.maxLaneResultBytes}, expansions=${budget.maxAssignmentExpansions}, " +
        "tool_calls=${budget.maxSpecialistToolCalls}, model_turns=${budget.maxSpecialistModelTurns}",
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

/**
 * A provider dimension that only becomes observable after the worker has finished, so no seam
 * could have stopped it. It is reported, never used to truncate or retry a lane.
 */
data class ReviewBudgetRegression(
  override val lane: String,
  override val budgetKind: String,
  override val configuredLimit: Long,
  override val observedValue: Long,
  override val packetDigest: String,
  override val assignmentDigest: String,
) : ReviewBudgetOutcome {
  override val enforceable: Boolean = false
  override val type: String = REVIEW_BUDGET_REGRESSION
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

  /**
   * Enforceable seams (a provider that reports usage mid-run) terminate the lane; a provider that
   * only reports totals once the worker exited yields a regression the caller records but cannot prevent.
   */
  fun providerUsageOutcome(
    identity: ReviewLaneIdentity,
    thresholds: ProviderTokenThresholds,
    usage: ProviderTokenUsage,
    enforceable: Boolean,
  ): ReviewBudgetOutcome? {
    val breach = listOf(
      "input_tokens" to (usage.inputTokens to thresholds.inputTokens),
      "cached_input_tokens" to (usage.cachedInputTokens to thresholds.cachedInputTokens),
      "output_tokens" to (usage.outputTokens to thresholds.outputTokens),
      "reasoning_tokens" to (usage.reasoningTokens to thresholds.reasoningTokens),
      "total_tokens" to (usage.totalTokens to thresholds.totalTokens),
    ).firstOrNull { (_, pair) -> pair.first != null && pair.first!! > pair.second } ?: return null
    val (kind, pair) = breach
    val (observed, limit) = pair
    return if (enforceable) {
      exceededOrNull(identity, kind, limit, observed!!)
    } else {
      ReviewBudgetRegression(identity.lane, kind, limit, observed!!, identity.packetDigest, identity.assignmentDigest)
    }
  }

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

fun requireRepositoryRelativePath(path: String) {
  require(path.isNotEmpty() && !path.startsWith('/') && !path.startsWith('\\')) {
    "Review paths must be repository-relative."
  }
  require('\u0000' !in path && path.none { Character.isSurrogate(it) }) {
    "Review paths must contain valid Unicode and no NUL."
  }
  require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
    "Review paths must use non-traversing Git path components."
  }
}

/** Injective UTF-8 length-prefixed encoding used by every content-addressed review identity. */
internal fun canonicalFields(vararg values: Any): String = values.joinToString("") { value ->
  val text = value.toString()
  "${text.toByteArray(StandardCharsets.UTF_8).size}:$text"
}

/** JSON scalar encoding keeps path data from becoming launch-payload structure. */
fun structuredString(value: String): String = buildString {
  append('"')
  value.forEach { char ->
    when (char) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000c' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
    }
  }
  append('"')
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(StandardCharsets.UTF_8))
  .joinToString("") { byte -> "%02x".format(byte) }
