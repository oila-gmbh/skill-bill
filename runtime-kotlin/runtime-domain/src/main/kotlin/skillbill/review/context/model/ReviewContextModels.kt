@file:Suppress("SpreadOperator", "MagicNumber")

package skillbill.review.context.model

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"

const val REVIEW_BUDGET_REGRESSION: String = "budget_regression"

const val REVIEW_RULE_EXCERPT_MAX_CHARS: Int = 2_000

/** Budget kinds for the parent-side routing analysis, reported on a loud routing-budget breach. */
const val REVIEW_ROUTING_ANALYSIS_PAIRS_BUDGET: String = "routing_analysis_pairs"

const val REVIEW_ROUTING_ANALYSIS_BYTES_BUDGET: String = "routing_analysis_bytes"

const val REVIEW_SPEC_INTENT_PROJECTION_BUDGET: String = "spec_intent_projection"

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }

/** A resolved depth is always a concrete tier, so it maps back onto the requested-mode vocabulary. */
fun ResolvedReviewExecutionMode.toCodeReviewExecutionMode(): skillbill.workflow.model.CodeReviewExecutionMode =
  when (this) {
    ResolvedReviewExecutionMode.INLINE -> skillbill.workflow.model.CodeReviewExecutionMode.INLINE
    ResolvedReviewExecutionMode.DELEGATED -> skillbill.workflow.model.CodeReviewExecutionMode.DELEGATED
  }

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

/** Closed-world policy for paths that are untracked at the selected review base. */
data class ReviewBaselineUntrackedPolicy(
  val includedPaths: List<String> = emptyList(),
  val excludedPaths: List<String> = emptyList(),
) {
  init {
    includedPaths.forEach(::requireRepositoryRelativePath)
    excludedPaths.forEach(::requireRepositoryRelativePath)
    require(includedPaths.distinct().size == includedPaths.size)
    require(excludedPaths.distinct().size == excludedPaths.size)
    require(includedPaths.intersect(excludedPaths.toSet()).isEmpty()) {
      "A baseline-untracked path cannot be both included and excluded."
    }
  }

  val canonical: String get() = canonicalFields(
    canonicalFields(*includedPaths.sorted().toTypedArray()),
    canonicalFields(*excludedPaths.sorted().toTypedArray()),
  )

  companion object {
    val EMPTY = ReviewBaselineUntrackedPolicy()
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

const val REVIEW_ROUTING_REASON_MAX_CHARS: Int = 600

/**
 * A commit/lane pair has exactly two final states. There is deliberately no deferred `candidate`
 * state: relevance is decided once, on the parent, and no worker re-decides it downstream.
 */
enum class ReviewCommitLaneDisposition { FOCUSED, SKIPPED }

/** One final, auditable routing decision for a single (commit unit, lane) pair. */
data class ReviewCommitLaneDecision(
  val commitSha: String,
  val orderIndex: Int,
  val lane: String,
  val disposition: ReviewCommitLaneDisposition,
  val reason: String,
  val signals: List<String> = emptyList(),
) {
  init {
    require(commitSha.isNotBlank()) { "Commit/lane decision commit identity must not be blank." }
    require(orderIndex >= 0) { "Commit/lane decision order index cannot be negative." }
    require(lane.isNotBlank()) { "Commit/lane decision lane must not be blank." }
    require(reason.isNotBlank()) {
      "Commit/lane decision '$commitSha'/'$lane' must carry a non-blank reason; a skip with no reason is unfalsifiable."
    }
    require(reason.length <= REVIEW_ROUTING_REASON_MAX_CHARS) {
      "Commit/lane decision reason exceeds the bounded limit of $REVIEW_ROUTING_REASON_MAX_CHARS characters."
    }
    require(signals.distinct().size == signals.size) { "Commit/lane decision signals must be unique." }
    require(signals.all(String::isNotBlank)) { "Commit/lane decision signals must not be blank." }
  }

  val focused: Boolean get() = disposition == ReviewCommitLaneDisposition.FOCUSED

  val canonical: String get() = canonicalFields(
    commitSha,
    orderIndex,
    lane,
    disposition.name,
    reason.replace("\r\n", "\n"),
    canonicalFields(*signals.sorted().toTypedArray()),
  )
}

/**
 * The complete commit-by-lane routing result: every analyzed pair carries one final disposition,
 * so a lane's assignment is derivable from focused commits alone with nothing left to re-decide.
 */
data class ReviewCommitLaneRoutingMatrix(
  val commitShas: List<String>,
  val lanes: List<String>,
  val decisions: List<ReviewCommitLaneDecision>,
) {
  init {
    require(commitShas.isNotEmpty()) { "A routing matrix must analyze at least one commit unit." }
    require(commitShas.distinct().size == commitShas.size) { "Routing matrix commit identities must be unique." }
    require(lanes.isNotEmpty()) { "A routing matrix must analyze at least one lane." }
    require(lanes.distinct().size == lanes.size) { "Routing matrix lanes must be unique." }
    val pairs = decisions.map { it.commitSha to it.lane }
    require(pairs.distinct().size == pairs.size) { "Routing matrix carries a duplicate commit/lane decision." }
    val expected = commitShas.flatMap { sha -> lanes.map { sha to it } }.toSet()
    val missing = expected - pairs.toSet()
    require(missing.isEmpty()) {
      "Routing matrix is missing a final decision for ${missing.size} commit/lane pair(s); every pair must be decided."
    }
    val unknown = pairs.toSet() - expected
    require(unknown.isEmpty()) { "Routing matrix decides commit/lane pairs it does not analyze: $unknown." }
    val orderBySha = commitShas.withIndex().associate { (index, sha) -> sha to index }
    require(decisions.all { it.orderIndex == orderBySha.getValue(it.commitSha) }) {
      "Routing matrix decision order index diverges from the analyzed commit order."
    }
  }

  /** The lane's focused commit identities in packet commit order. */
  fun focusedCommits(lane: String): List<String> = decisions
    .filter { it.lane == lane && it.focused }
    .sortedBy { it.orderIndex }
    .map { it.commitSha }

  fun decisionsFor(lane: String): List<ReviewCommitLaneDecision> = decisions
    .filter { it.lane == lane }
    .sortedBy { it.orderIndex }

  val focusedPairCount: Int get() = decisions.count { it.focused }
  val analyzedPairCount: Int get() = decisions.size

  val canonical: String get() = canonicalFields(
    canonicalFields(*commitShas.toTypedArray()),
    canonicalFields(*lanes.toTypedArray()),
    canonicalFields(
      *decisions.sortedWith(compareBy({ it.orderIndex }, { it.lane })).map { it.canonical }.toTypedArray(),
    ),
  )

  val routingDigest: String get() = sha256(canonical)
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
  val maxRoutingAnalysisPairs: Int = 4_096,
  val maxRoutingAnalysisBytes: Long = 33_554_432,
  val maxSpecIntentProjectionBytes: Long = 32_768,
  val providerTokenThresholds: ProviderTokenThresholds = ProviderTokenThresholds(),
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
  val assignedBundle: ReviewLaneBundle = ReviewLaneBundle.EMPTY,
  /** This lane's column of the routing matrix: why each commit was focused into or skipped for it. */
  val laneRouting: List<ReviewCommitLaneDecision> = emptyList(),
  val criteriaReferences: List<String> = emptyList(),
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val reviewRevision: ReviewRevision,
  val laneDecision: ReviewLaneDecision,
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val expansions: List<ReviewExpansionRecord> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
) {
  init {
    require(reviewId.isNotBlank() && lane.isNotBlank() && baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(packetDigest.matches(SHA256_HEX)) { "Packet digest must be lowercase SHA-256." }
    require(assignedPaths.distinct().size == assignedPaths.size) { "Assigned paths must be unique." }
    assignedPaths.forEach(::requireRepositoryRelativePath)
    require(assignedHunks.distinct().size == assignedHunks.size) { "Assigned hunk ids must be unique." }
    // Full coverage is asserted where the packet is in hand (preparation validation and launch);
    // here the bundle can only be checked against the assignment's own hunk surface.
    require(assignedBundle.hunkIds.all { it in assignedHunks }) {
      "Assignment '$lane' bundle attributes hunks that are not assigned to the lane."
    }
    require(laneRouting.all { it.lane == lane }) { "Assignment '$lane' carries routing for a different lane." }
    require(laneRouting.map { it.commitSha }.distinct().size == laneRouting.size) {
      "Assignment '$lane' routing repeats a commit."
    }
    require(laneRouting.map { it.orderIndex }.zipWithNext().all { (previous, next) -> previous < next }) {
      "Assignment '$lane' routing must preserve packet commit order."
    }
    // A focused commit contributes no bundle entry when it owns no hunk under the lane's paths, so
    // the bundle is a subset here; exact equality is asserted where the packet's hunks are in hand.
    // Only the routing column's explicit skips are rejected here; commits absent from the column
    // (outside the packet) are owned by packet validation so the reason stays specific.
    val explicitlySkipped = laneRouting.filterNot { it.focused }.map { it.commitSha }.toSet()
    val fromSkipped = assignedBundle.entries.map { it.commitSha }.filter { it in explicitlySkipped }
    require(fromSkipped.isEmpty()) {
      "Assignment '$lane' claims hunks from commits routing skipped for the lane: ${fromSkipped.sorted()}."
    }
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
        assignedBundle.canonical,
        canonicalFields(*laneRouting.sortedBy { it.orderIndex }.map { it.canonical }.toTypedArray()),
        canonicalFields(*criteriaReferences.sorted().toTypedArray()),
        canonicalFields(*matchedRules.map { it.canonical }.sorted().toTypedArray()),
        canonicalFields(*evidenceTargets.map { it.canonical }.sorted().toTypedArray()),
        dependencyAllowlist.canonical,
        baselineUntrackedPolicy.canonical,
      ).let { canonicalFields(*it.toTypedArray()) }.replace("\r\n", "\n"),
    )
}

/** Placeholder identity prefix for a review unit that is not a real commit; never a fabricated SHA. */
const val REVIEW_SYNTHETIC_COMMIT_PREFIX: String = "synthetic:"

enum class ReviewCommitSource {
  COMMIT_RANGE,
  SYNTHETIC_WORKING_TREE,
  SYNTHETIC_SUPPLIED_DIFF,
  SYNTHETIC_AGGREGATE_PR_DIFF,
  ;

  val isSynthetic: Boolean get() = this != COMMIT_RANGE
}

/** One ordered review unit: a real commit's incremental hunks, or a synthetic whole-delta unit. */
data class ReviewCommitUnit(
  val commitSha: String,
  val parentSha: String,
  val subject: String,
  val orderIndex: Int,
  val hunks: List<ReviewChangedHunk>,
  val source: ReviewCommitSource,
) {
  init {
    require(commitSha.isNotBlank()) { "Review commit unit identity must not be blank." }
    require(parentSha.isNotBlank()) { "Review commit unit parent identity must not be blank." }
    require(orderIndex >= 0) { "Review commit unit order index cannot be negative." }
    require(hunks.map { it.hunkId }.distinct().size == hunks.size) {
      "Review commit unit '$commitSha' repeats a hunk id; a commit owns each hunk exactly once."
    }
    val ownScope = commitScopeKey(commitSha, orderIndex)
    require(hunks.all { it.commitScope == null || (!source.isSynthetic && it.commitScope == ownScope) }) {
      "Review commit unit '$commitSha' owns a hunk scoped to a different commit."
    }
    require(
      hunks.map { it.path to listOf(it.oldStart, it.newStart) }.distinct().size == hunks.size,
    ) { "Review commit unit '$commitSha' carries two hunks at the same position in one file." }
    if (source.isSynthetic) {
      require(
        commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX) && parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX),
      ) {
        "Synthetic review unit from source '$source' must carry a '$REVIEW_SYNTHETIC_COMMIT_PREFIX' placeholder " +
          "identity, never a fabricated commit SHA."
      }
      require(orderIndex == 0) { "A synthetic review unit is the sole unit of its packet and must be first." }
    } else {
      require(
        !commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX) && !parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX),
      ) { "A COMMIT_RANGE review unit must carry real Git identities, not synthetic placeholders." }
    }
  }

  /**
   * A commit owns its hunks as a set, so every identity and projection reads this order rather than
   * the order a fact port or parser happened to enumerate them in. The unit's own uniqueness
   * invariant makes (path, newStart, oldStart) a total order over its hunks.
   */
  val canonicalHunks: List<ReviewChangedHunk>
    get() = hunks.sortedWith(
      compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart, ReviewChangedHunk::oldStart),
    )

  val hunkIds: List<String> get() = canonicalHunks.map { it.hunkId }

  val commitUnitId: String by lazy(LazyThreadSafetyMode.PUBLICATION) { sha256(canonicalValue()) }

  internal fun canonicalValue(): String = canonicalFields(
    commitSha,
    parentSha,
    subject.replace("\r\n", "\n"),
    orderIndex,
    source.name,
    canonicalFields(*canonicalHunks.map { it.packetCanonical() }.toTypedArray()),
  )

  companion object {
    fun commitScopeKey(commitSha: String, orderIndex: Int): String = "$commitSha@$orderIndex"

    /** Builds a COMMIT_RANGE unit, scoping every hunk to this commit so its identity is commit-owned. */
    fun ofCommit(
      commitSha: String,
      parentSha: String,
      subject: String,
      orderIndex: Int,
      hunks: List<ReviewChangedHunk>,
    ): ReviewCommitUnit {
      val scope = commitScopeKey(commitSha, orderIndex)
      return ReviewCommitUnit(
        commitSha = commitSha,
        parentSha = parentSha,
        subject = subject,
        orderIndex = orderIndex,
        hunks = hunks.map { if (it.commitScope == scope) it else it.copy(commitScope = scope) },
        source = ReviewCommitSource.COMMIT_RANGE,
      )
    }

    fun synthetic(source: ReviewCommitSource, hunks: List<ReviewChangedHunk>): ReviewCommitUnit {
      require(source.isSynthetic) { "A synthetic review unit cannot declare the COMMIT_RANGE source." }
      return ReviewCommitUnit(
        commitSha = REVIEW_SYNTHETIC_COMMIT_PREFIX + source.name.lowercase(),
        parentSha = REVIEW_SYNTHETIC_COMMIT_PREFIX + "base",
        subject = "synthetic review unit for ${source.name.lowercase()}",
        orderIndex = 0,
        hunks = hunks,
        source = source,
      )
    }
  }
}

/**
 * The checked base-to-head equivalence fact: the ordered units cover the authoritative delta with
 * no silent omission or duplication. A unit sequence that cannot assert the chain must say why.
 */
data class ReviewCommitCoverageFact(
  val baseRevision: String,
  val headRevision: String,
  val commitCount: Int,
  val chainVerified: Boolean,
  val pathCoverageVerified: Boolean,
  val degradedReason: String? = null,
) {
  init {
    require(baseRevision.isNotBlank() && headRevision.isNotBlank()) {
      "Commit coverage fact must carry non-blank base and head revisions."
    }
    require(commitCount >= 1) { "Commit coverage fact must describe at least one review unit." }
    require(degradedReason == null || degradedReason.isNotBlank()) {
      "A commit coverage fact degraded reason must not be blank."
    }
    require((chainVerified && pathCoverageVerified) || !degradedReason.isNullOrBlank()) {
      "An unverified commit coverage fact must name the reason it could not be verified."
    }
  }

  val canonical: String get() = canonicalFields(
    baseRevision,
    headRevision,
    commitCount,
    chainVerified,
    pathCoverageVerified,
    degradedReason.orEmpty(),
  )
}

/** One lane's assigned hunks grouped by their owning commit, in packet commit order. */
data class ReviewLaneBundleEntry(val commitSha: String, val orderIndex: Int, val hunkIds: List<String>) {
  init {
    require(commitSha.isNotBlank()) { "Lane bundle entry commit identity must not be blank." }
    require(orderIndex >= 0) { "Lane bundle entry order index cannot be negative." }
    require(hunkIds.isNotEmpty()) { "Lane bundle entry for '$commitSha' must carry at least one hunk." }
    require(hunkIds.distinct().size == hunkIds.size) { "Lane bundle entry hunk ids must be unique." }
  }

  val canonical: String get() = canonicalFields(commitSha, orderIndex, canonicalFields(*hunkIds.toTypedArray()))
}

data class ReviewLaneBundle(val entries: List<ReviewLaneBundleEntry> = emptyList()) {
  init {
    require(entries.map { it.commitSha }.distinct().size == entries.size) {
      "Lane bundle must carry one entry per commit."
    }
    require(entries.map { it.orderIndex }.zipWithNext().all { (previous, next) -> previous < next }) {
      "Lane bundle entries must preserve packet commit order."
    }
    val ids = entries.flatMap { it.hunkIds }
    require(ids.distinct().size == ids.size) { "Lane bundle claims a hunk id under more than one commit." }
  }

  val hunkIds: List<String> get() = entries.flatMap { it.hunkIds }
  val canonical: String get() = canonicalFields(*entries.map { it.canonical }.toTypedArray())
  val bundleDigest: String get() = sha256(canonical)

  companion object {
    val EMPTY: ReviewLaneBundle = ReviewLaneBundle()
  }
}

data class ReviewHunkEvidenceLocator(
  val storePath: String,
  val hunkHeader: String,
  val payloadFile: String = PAYLOAD_FILE,
) {
  init {
    requireRepositoryRelativePath(storePath)
    require(payloadFile == PAYLOAD_FILE) { "Hunk evidence payload file must be '$PAYLOAD_FILE'." }
    require(hunkHeader.isNotBlank()) { "Hunk evidence locator header must not be blank." }
  }

  val canonical: String get() = canonicalFields(storePath, payloadFile, hunkHeader)

  companion object {
    const val PAYLOAD_FILE: String = "diff.patch"

    fun header(oldStart: Int, oldCount: Int, newStart: Int, newCount: Int): String =
      "@@ -$oldStart,$oldCount +$newStart,$newCount @@"

    fun inProcess(
      contentDigest: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
    ): ReviewHunkEvidenceLocator = ReviewHunkEvidenceLocator(
      storePath = ".skill-bill/run-evidence/in-process/$contentDigest",
      hunkHeader = header(oldStart, oldCount, newStart, newCount),
    )

    fun atStore(
      storePath: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
    ): ReviewHunkEvidenceLocator = ReviewHunkEvidenceLocator(
      storePath = storePath,
      hunkHeader = header(oldStart, oldCount, newStart, newCount),
    )
  }
}

data class ReviewChangedHunk(
  val path: String,
  val oldStart: Int,
  val oldCount: Int,
  val newStart: Int,
  val newCount: Int,
  val content: String,
  val commitScope: String? = null,
  internal val indexedContentDigest: String? = null,
  internal val indexedEvidenceLocator: ReviewHunkEvidenceLocator? = null,
  internal val indexedHunkId: String? = null,
) {
  init {
    requireRepositoryRelativePath(path)
    require(oldStart >= 0 && oldCount >= 0 && newStart >= 0 && newCount >= 0)
    require(commitScope == null || commitScope.isNotBlank()) { "Changed hunk commit scope must not be blank." }
    indexedContentDigest?.let { require(it.matches(SHA256_HEX)) { "Changed hunk content digest must be lowercase SHA-256." } }
    indexedHunkId?.let { require(it.matches(SHA256_HEX)) { "Changed hunk id must be lowercase SHA-256." } }
  }

  val contentDigest: String = indexedContentDigest ?: sha256(content.replace("\r\n", "\n"))

  val evidenceLocator: ReviewHunkEvidenceLocator = indexedEvidenceLocator
    ?: ReviewHunkEvidenceLocator.inProcess(contentDigest, oldStart, oldCount, newStart, newCount)

  val hunkId: String = indexedHunkId ?: sha256(identityCanonical())

  internal fun identityCanonical(): String = identityCanonical(
    path,
    oldStart,
    oldCount,
    newStart,
    newCount,
    content,
    commitScope,
  )

  internal fun packetCanonical(): String = canonicalFields(
    hunkId,
    oldStart,
    oldCount,
    newStart,
    newCount,
    contentDigest,
    evidenceLocator.canonical,
  )

  fun asIndex(locator: ReviewHunkEvidenceLocator, body: String): ReviewChangedHunk {
    val normalized = body.replace("\r\n", "\n")
    return copy(
      content = "",
      indexedContentDigest = digestOfBody(normalized),
      indexedEvidenceLocator = locator,
      indexedHunkId = idFor(path, oldStart, oldCount, newStart, newCount, normalized, commitScope),
    )
  }

  companion object {
    fun digestOfBody(body: String): String = sha256(body.replace("\r\n", "\n"))

    fun idFor(
      path: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
      body: String,
      commitScope: String?,
    ): String = sha256(identityCanonical(path, oldStart, oldCount, newStart, newCount, body, commitScope))

    fun fromBody(
      path: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
      body: String,
      commitScope: String? = null,
    ): ReviewChangedHunk = ReviewChangedHunk(path, oldStart, oldCount, newStart, newCount, body, commitScope)

    private fun identityCanonical(
      path: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
      body: String,
      commitScope: String?,
    ): String = canonicalFields(
      path,
      oldStart,
      oldCount,
      newStart,
      newCount,
      body.replace("\r\n", "\n"),
      commitScope.orEmpty(),
    )
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
  val commitUnits: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
  val routingMatrix: ReviewCommitLaneRoutingMatrix,
  val reviewRevision: ReviewRevision,
  val laneDecisions: List<ReviewLaneDecision>,
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val learningsReferences: List<ReviewLearningsReference> = emptyList(),
  val buildTestFacts: List<ReviewBuildTestFact> = emptyList(),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val expansionLedger: List<ReviewExpansionRecord> = emptyList(),
  val composedLayers: List<String> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
) {
  init {
    require(reviewId.isNotBlank() && repositoryIdentity.isNotBlank())
    require(baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(selectedLanes.isNotEmpty() && selectedLanes.distinct().size == selectedLanes.size)
    require(addOns.distinct().size == addOns.size)
    require(composedLayers.all(String::isNotBlank) && composedLayers.distinct().size == composedLayers.size)
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
    requireCommitEvidence(ownedHunks)
    requireRoutingMatrix()
  }

  /**
   * Sparse routing decides lane selection: a lane survives exactly when it focused at least one
   * commit, so a selected lane with no focused commit — or a dropped lane that focused one — is a
   * routing/selection contradiction rather than a recoverable state.
   */
  private fun requireRoutingMatrix() {
    val orderedShas = commitUnits.sortedBy { it.orderIndex }.map { it.commitSha }
    require(routingMatrix.commitShas == orderedShas) {
      "Routing matrix commit order does not match the packet commit sequence."
    }
    val unanalyzed = selectedLanes.filterNot { it in routingMatrix.lanes }
    require(unanalyzed.isEmpty()) { "Selected lanes were never routed: ${unanalyzed.sorted()}." }
    val unfocused = selectedLanes.filter { routingMatrix.focusedCommits(it).isEmpty() }
    require(unfocused.isEmpty()) {
      "Selected lanes focused no commit, so they would review nothing: ${unfocused.sorted()}."
    }
  }

  /** The hunks a lane may claim: its owned paths restricted to the commits routing focused for it. */
  fun focusedHunkIds(laneDecision: ReviewLaneDecision): Set<String> {
    val focused = routingMatrix.focusedCommits(laneDecision.lane).toSet()
    val ownedPaths = laneDecision.normalizedOwnedPaths.toSet()
    return commitUnits.filter { it.commitSha in focused }
      .flatMap { unit -> unit.canonicalHunks.filter { it.path in ownedPaths }.map { it.hunkId } }
      .toSet()
  }

  @Suppress("CyclomaticComplexMethod")
  private fun requireCommitEvidence(ownedHunks: Set<String>) {
    require(commitUnits.isNotEmpty()) {
      "A review packet is missing its commit sequence; at least one unit is required."
    }
    require(commitUnits.map { it.commitSha }.distinct().size == commitUnits.size) {
      "Review packet carries a duplicate commit identity."
    }
    require(commitUnits.map { it.orderIndex }.sorted() == commitUnits.indices.toList()) {
      "Review packet commit units are out of order; order indices must form a contiguous 0..n-1 sequence."
    }
    require(coverageFact.commitCount == commitUnits.size) {
      "Coverage fact counts ${coverageFact.commitCount} commits but the packet carries ${commitUnits.size}."
    }
    val ordered = commitUnits.sortedBy { it.orderIndex }
    if (ordered.any { it.source.isSynthetic }) {
      require(ordered.size == 1) { "A synthetic review unit must be the only unit in its packet." }
    } else {
      require(ordered.first().parentSha == baseRevision) {
        "Review packet commit chain does not start at the base revision '$baseRevision'."
      }
      require(ordered.last().commitSha == headRevision) {
        "Review packet commit chain does not end at the head revision '$headRevision'."
      }
      ordered.zipWithNext().forEach { (previous, next) ->
        require(next.parentSha == previous.commitSha) {
          "Review packet commit chain is broken: '${next.commitSha}' does not descend from '${previous.commitSha}'."
        }
      }
    }
    val unitHunkIds = ordered.flatMap { it.hunkIds }
    require(unitHunkIds.distinct().size == unitHunkIds.size) {
      "A changed hunk is claimed by more than one commit unit; commit units must partition the packet hunks."
    }
    val absent = unitHunkIds.filterNot { it in ownedHunks }
    require(absent.isEmpty()) { "A commit unit references a hunk absent from the packet changed hunks." }
    val unowned = ownedHunks - unitHunkIds.toSet()
    require(unowned.isEmpty()) { "Packet changed hunks are unowned by any commit unit: ${unowned.size} hunk(s)." }
  }

  val ownedCommitIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    commitUnits.map { it.commitSha }.toSet()
  }

  /**
   * Stable identity of the reviewed commit sequence, over the ordered commit unit ids alone. It is
   * deliberately narrower than [digest]: lane selection or a rule excerpt changing must not change
   * which sequence a lane or integration result claims to cover, but reordering commits must.
   */
  val commitSequenceDigest: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
    sha256(canonicalFields(*commitUnits.sortedBy { it.orderIndex }.map { it.commitUnitId }.toTypedArray()))
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
    changedHunks.sortedBy { it.packetCanonical() }
      .map { it.packetCanonical() }.let { canonicalFields(*it.toTypedArray()) },
    // Declared order, never sorted by content: reordering commits must stay digest-visible.
    commitUnits.sortedBy { it.orderIndex }
      .map { it.canonicalValue() }.let { canonicalFields(*it.toTypedArray()) },
    coverageFact.canonical,
    routingMatrix.canonical,
    canonicalFields(*matchedRules.map { it.canonical }.sorted().toTypedArray()),
    canonicalFields(*learningsReferences.map { it.canonical }.sorted().toTypedArray()),
    canonicalFields(*buildTestFacts.map { it.canonical }.sorted().toTypedArray()),
    dependencyAllowlist.canonical,
    baselineUntrackedPolicy.canonical,
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
    require(assignment.baselineUntrackedPolicy == packet.baselineUntrackedPolicy) {
      "Launch baseline-untracked policy differs from the packet policy."
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
    // Sparse routing owns the expected surface: a lane sees its owned paths only in the commits it
    // focused, never every hunk that ever touched those paths.
    val expectedHunks = packet.focusedHunkIds(packetDecision)
    require(assignment.assignedHunks.toSet() == expectedHunks) {
      "Launch hunks differ from the focused-commit hunks the packet routed to the lane."
    }
    require(assignment.laneRouting == packet.routingMatrix.decisionsFor(assignment.lane)) {
      "Launch lane routing differs from the packet routing matrix for lane '${assignment.lane}'."
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
    requireBundleMatchesPacket()
  }

  private fun requireBundleMatchesPacket() {
    val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
    val outside = assignment.assignedBundle.entries.map { it.commitSha }.filterNot { it in unitsBySha }
    require(outside.isEmpty()) {
      "Launch assignment claims commit units outside its packet: ${outside.sorted()}."
    }
    assignment.assignedBundle.entries.forEach { entry ->
      val unit = unitsBySha.getValue(entry.commitSha)
      require(entry.orderIndex == unit.orderIndex) {
        "Launch bundle entry '${entry.commitSha}' order differs from the packet commit order."
      }
      val strayHunks = entry.hunkIds.filterNot { it in unit.hunkIds }
      require(strayHunks.isEmpty()) {
        "Launch bundle attributes hunks to commit '${entry.commitSha}' that the commit does not own."
      }
    }
    require(assignment.assignedBundle.hunkIds.toSet() == assignment.assignedHunks.toSet()) {
      "Launch bundle must cover exactly the assigned hunks so every projected body is commit-attributable."
    }
  }

  /** Assigned commit units in packet order (identity metadata for the launch envelope). */
  private val projectedUnits: List<ReviewCommitUnit>
    get() {
      val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
      return assignment.assignedBundle.entries.map { entry -> unitsBySha.getValue(entry.commitSha) }
    }

  /** Ordered assigned hunk bodies with commit identity; never the aggregate PR diff. */
  val assembledBundle: ReviewLaneAssembledBundle by lazy(LazyThreadSafetyMode.PUBLICATION) {
    ReviewLaneAssembledBundle.assemble(assignment, packet)
  }

  /**
   * Size-driven segmentation of [assembledBundle]. Segments are consumed inside one lane worker
   * operation; segmentation never multiplies worker launches.
   */
  val segmentation: ReviewLaneBundleSegmentation by lazy(LazyThreadSafetyMode.PUBLICATION) {
    segmentAssembledBundle(assembledBundle, budget.maxLaneLaunchBytes, ::measureBundleEntries)
  }

  val completionState: ReviewLaneCompletionState by lazy(LazyThreadSafetyMode.PUBLICATION) {
    segmentation.toCompletionState(assembledBundle.compositionDigest)
  }

  fun requireCodexForkTurns(forkTurns: String?) {
    require(forkTurns == "none") { "Governed Codex review launches require fork_turns none." }
  }

  /** Entries the segmentation retained; unreviewable bodies are named, never delivered. */
  val deliveredEntries: List<ReviewLaneAssembledEntry> get() = segmentation.segments.flatMap { it.entries }

  val canonicalPayload: String get() = renderCanonicalPayload(deliveredEntries, segmentation)

  /**
   * Returns a typed budget breach when the fixed launch overhead alone exceeds the lane budget
   * (nothing can be reviewed), or when the rendered payload exceeds the lane allowance. Each
   * segment is separately accounted against [ReviewContextBudgetPolicy.maxLaneLaunchBytes], so the
   * allowance for a delivered payload is that budget once per segment; anything beyond it is an
   * overflow the segmentation did not account for and must surface as a typed breach rather than
   * ship silently.
   */
  fun budgetOutcomeOrNull(): ReviewContextBudgetExceeded? {
    val overhead = measureBundleEntries(emptyList())
    val renderedBytes = canonicalPayload.toByteArray(StandardCharsets.UTF_8).size.toLong()
    val allowance = budget.maxLaneLaunchBytes * maxOf(1, segmentation.segments.size)
    return if (overhead > budget.maxLaneLaunchBytes || renderedBytes > allowance) {
      ReviewContextBudgetExceeded(
        lane = assignment.lane,
        budgetKind = "lane_launch_bytes",
        configuredLimit = budget.maxLaneLaunchBytes,
        observedValue = maxOf(overhead, renderedBytes),
        packetDigest = assignment.packetDigest,
        assignmentDigest = assignment.digest,
        enforceable = true,
      )
    } else {
      null
    }
  }

  private fun measureBundleEntries(entries: List<ReviewLaneAssembledEntry>): Long {
    val synthetic = if (entries.isEmpty()) {
      ReviewLaneBundleSegmentation(emptyList(), emptyList(), budget.maxLaneLaunchBytes)
    } else {
      ReviewLaneBundleSegmentation(
        segments = listOf(
          ReviewLaneBundleSegment(segmentId = "measure", entries = entries, measuredBytes = 1),
        ),
        unreviewableEntries = emptyList(),
        budgetLimitBytes = budget.maxLaneLaunchBytes,
      )
    }
    return renderCanonicalPayload(entries, synthetic).toByteArray(StandardCharsets.UTF_8).size.toLong()
  }

  private fun renderCanonicalPayload(
    entries: List<ReviewLaneAssembledEntry>,
    segments: ReviewLaneBundleSegmentation,
  ): String = buildString {
    appendLaunchIdentity()
    appendGovernanceText()
    appendAssignedSurface()
    appendBundleSection(entries, segments)
    appendPolicySurface()
  }.trimEnd()

  private fun StringBuilder.appendLaunchIdentity() {
    appendLine("contract_version: \"$REVIEW_CONTEXT_CONTRACT_VERSION\"")
    appendLine("kind: launch")
    appendLine("review_id: ${assignment.reviewId}")
    appendLine("review_revision: ${assignment.reviewRevision.sessionId}@${assignment.reviewRevision.runRevision}")
    appendLine("packet_digest: ${assignment.packetDigest}")
    appendLine("assignment_digest: ${assignment.digest}")
    appendLine("lane: ${assignment.lane}")
    appendLine("base_revision: ${assignment.baseRevision}")
    appendLine("head_revision: ${assignment.headRevision}")
    appendLine("broker_id: $brokerId")
  }

  private fun StringBuilder.appendGovernanceText() {
    appendLine("specialist_contract: |")
    specialistContract.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    appendLine("rubric: |")
    rubric.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    appendLine("consumer_contract: |")
    ReviewPacketConsumerContract.CONSUMER_CONTRACT.lineSequence().forEach { appendLine("  $it") }
  }

  private fun StringBuilder.appendAssignedSurface() {
    appendLine("assigned_paths:")
    assignment.assignedPaths.sorted().forEach { appendLine("  - ${structuredString(it)}") }
    appendLine("assigned_hunks:")
    assignment.assignedHunks.sorted().forEach { appendLine("  - $it") }
    appendLine(
      "coverage_fact: base=${packet.coverageFact.baseRevision}, head=${packet.coverageFact.headRevision}, " +
        "commits=${packet.coverageFact.commitCount}, chain_verified=${packet.coverageFact.chainVerified}, " +
        "path_coverage_verified=${packet.coverageFact.pathCoverageVerified}, " +
        "degraded_reason=${structuredString(packet.coverageFact.degradedReason.orEmpty())}",
    )
    appendLine("assigned_commit_units:")
    projectedUnits.forEach { unit ->
      appendLine("  - commit_sha: ${structuredString(unit.commitSha)}")
      appendLine("    parent_sha: ${structuredString(unit.parentSha)}")
      appendLine("    subject: ${structuredString(unit.subject.replace("\r\n", "\n"))}")
      appendLine("    order_index: ${unit.orderIndex}")
      appendLine("    source: ${unit.source.name.lowercase()}")
    }
    appendLine("lane_routing:")
    assignment.laneRouting.forEach { decision ->
      appendLine("  - commit_sha: ${structuredString(decision.commitSha)}")
      appendLine("    order_index: ${decision.orderIndex}")
      appendLine("    disposition: ${decision.disposition.name.lowercase()}")
      appendLine("    reason: ${structuredString(decision.reason)}")
    }
  }

  private fun StringBuilder.appendBundleSection(
    entries: List<ReviewLaneAssembledEntry>,
    segments: ReviewLaneBundleSegmentation,
  ) {
    val disposition = if (segments.incomplete) {
      ReviewLaneReviewDisposition.INCOMPLETE
    } else {
      ReviewLaneReviewDisposition.COMPLETE
    }
    appendLine("bundle:")
    appendLine("  composition_digest: ${assembledBundle.compositionDigest}")
    appendLine("  lane_disposition: ${disposition.wireValue}")
    if (segments.incomplete) {
      appendLine("  budget_dimension: lane_launch_bytes")
    }
    appendLine("  unreviewed_segment_ids:")
    segments.unreviewedSegmentIds.forEach { appendLine("    - $it") }
    appendLine("  entries:")
    entries.forEach { entry ->
      appendLine("    - commit_sha: ${structuredString(entry.commitSha)}")
      appendLine("      parent_sha: ${structuredString(entry.parentSha)}")
      appendLine("      subject: ${structuredString(entry.subject.replace("\r\n", "\n"))}")
      appendLine("      order_index: ${entry.orderIndex}")
      appendLine("      path: ${structuredString(entry.hunk.path)}")
      appendLine("      hunk_id: ${entry.hunkId}")
      appendLine("      old_start: ${entry.hunk.oldStart}")
      appendLine("      old_count: ${entry.hunk.oldCount}")
      appendLine("      new_start: ${entry.hunk.newStart}")
      appendLine("      new_count: ${entry.hunk.newCount}")
      appendLine("      content_digest: ${entry.hunk.contentDigest}")
      appendLine("      evidence_locator:")
      appendLine("        store_path: ${structuredString(entry.hunk.evidenceLocator.storePath)}")
      appendLine("        payload_file: ${structuredString(entry.hunk.evidenceLocator.payloadFile)}")
      appendLine("        hunk_header: ${structuredString(entry.hunk.evidenceLocator.hunkHeader)}")
    }
    appendLine("  segments:")
    segments.segments.forEach { segment ->
      appendLine("    - segment_id: ${segment.segmentId}")
      appendLine("      measured_bytes: ${segment.measuredBytes}")
      appendLine("      composition_digest: ${segment.compositionDigest}")
      appendLine("      entries:")
      segment.entries.forEach { entry ->
        appendLine("        - commit_sha: ${structuredString(entry.commitSha)}")
        appendLine("          order_index: ${entry.orderIndex}")
        appendLine("          hunk_id: ${entry.hunkId}")
        appendLine("          path: ${structuredString(entry.hunk.path)}")
      }
    }
  }

  private fun StringBuilder.appendPolicySurface() {
    appendLine("criteria_references:")
    assignment.criteriaReferences.sorted().forEach { appendLine("  - $it") }
    appendLine("matched_rules:")
    assignment.matchedRules.sortedBy { it.ruleId }.forEach { rule ->
      appendLine("  - rule_id: ${structuredString(rule.ruleId)}")
      appendLine("    source_path: ${structuredString(rule.sourcePath)}")
      appendLine("    excerpt: |")
      rule.excerpt.replace("\r\n", "\n").lineSequence().forEach { appendLine("      $it") }
      appendLine("    digest: ${rule.digest}")
    }
    appendLine("evidence_targets:")
    assignment.evidenceTargets.map { it.targetId }.sorted().forEach { appendLine("  - $it") }
    appendLine("dependency_allowlist:")
    assignment.dependencyAllowlist.normalized.sorted().forEach { appendLine("  - ${structuredString(it)}") }
    appendLine("baseline_untracked_policy:")
    appendLine("  included_paths:")
    assignment.baselineUntrackedPolicy.includedPaths.sorted()
      .forEach { appendLine("    - ${structuredString(it)}") }
    appendLine("  excluded_paths:")
    assignment.baselineUntrackedPolicy.excludedPaths.sorted()
      .forEach { appendLine("    - ${structuredString(it)}") }
    appendLine("forbidden_rediscovery:")
    ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.forEach { appendLine("  - $it") }
    appendLine("evidence_surface_rules: |")
    ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES.lineSequence().forEach { appendLine("  $it") }
    appendLine("report_structure: |")
    ReviewPacketConsumerContract.REPORT_STRUCTURE.lineSequence().forEach { appendLine("  $it") }
    appendLine(
      "budgets: launch=${budget.maxLaneLaunchBytes}, evidence=${budget.maxLaneEvidenceBytes}, " +
        "result=${budget.maxLaneResultBytes}, expansions=${budget.maxAssignmentExpansions}, " +
        "tool_calls=${budget.maxSpecialistToolCalls}, model_turns=${budget.maxSpecialistModelTurns}",
    )
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
  require('\u0000' !in path && path.hasWellFormedUtf16()) {
    "Review paths must contain valid Unicode and no NUL."
  }
  require(!WINDOWS_ABSOLUTE_PATH.matches(path)) { "Review paths must be repository-relative." }
  require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
    "Review paths must use non-traversing Git path components."
  }
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")

private fun String.hasWellFormedUtf16(): Boolean {
  var index = 0
  while (index < length) {
    val current = this[index]
    when {
      Character.isHighSurrogate(current) -> {
        if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
        index += 2
      }
      Character.isLowSurrogate(current) -> return false
      else -> index++
    }
  }
  return true
}

/** Injective UTF-8 length-prefixed encoding used by every content-addressed review identity. */
internal fun canonicalFields(vararg values: Any): String = canonicalFieldList(values.asList())

/** List form of [canonicalFields] for callers that already hold a collection. */
internal fun canonicalFieldList(values: List<Any>): String = values.joinToString("") { value ->
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

/** A resolved review depth together with the named rule that decided it, reported in review metadata. */
data class ResolvedReviewDepth(
  val resolvedMode: ResolvedReviewExecutionMode,
  val decidingRule: String,
)
