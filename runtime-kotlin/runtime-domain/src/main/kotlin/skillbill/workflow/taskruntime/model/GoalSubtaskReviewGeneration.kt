package skillbill.workflow.taskruntime.model

private const val MAX_REVIEW_REFERENCE_LENGTH = 512
private val REVIEW_DIGEST = Regex("^[0-9a-f]{64}$")

data class GoalSubtaskReviewGenerationIdentity(
  val workflowId: String,
  val generationId: String,
  val reviewBase: String,
  val reviewedDeltaDigest: String,
  val repositoryCheckpoint: String,
) {
  init {
    requireBounded("workflowId", workflowId)
    requireBounded("generationId", generationId)
    requireBounded("reviewBase", reviewBase)
    require(REVIEW_DIGEST.matches(reviewedDeltaDigest)) {
      "reviewedDeltaDigest must be a lowercase SHA-256 digest."
    }
    requireBounded("repositoryCheckpoint", repositoryCheckpoint)
  }
}

data class GoalSubtaskReviewGeneration(
  val identity: GoalSubtaskReviewGenerationIdentity,
  val passNumbers: List<Int> = emptyList(),
  val supersededByGenerationId: String? = null,
) {
  init {
    require(passNumbers == passNumbers.distinct().sorted()) {
      "Review generation pass numbers must be sorted and unique."
    }
    require(passNumbers.all { it in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES }) {
      "Review generation pass numbers must respect the review cap."
    }
    supersededByGenerationId?.let { requireBounded("supersededByGenerationId", it) }
  }
}

enum class GoalSubtaskReviewFindingDisposition(val wireValue: String, val terminal: Boolean) {
  UNRESOLVED("unresolved", false),
  RESOLVED("resolved", true),
  STILL_PRESENT("still_present", false),
  SUPERSEDED("superseded", true),
  ACCEPTED("accepted", true),
  ;
}

data class GoalSubtaskReviewFinding(
  val findingId: String,
  val severity: String,
  val category: String,
  val location: String,
  val summary: String,
  val sourceGenerationId: String,
) {
  init {
    requireBounded("findingId", findingId)
    require(severity in setOf("blocker", "major", "minor", "nit")) {
      "Review finding severity is not governed."
    }
    requireBounded("category", category)
    requireBounded("location", location)
    requireBounded("summary", summary)
    requireBounded("sourceGenerationId", sourceGenerationId)
  }

  val isBlocker: Boolean get() = severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
}

data class GoalSubtaskReviewFindingDispositionRecord(
  val workflowId: String,
  val generationId: String,
  val findingId: String,
  val disposition: GoalSubtaskReviewFindingDisposition,
  val evidence: List<String>,
) {
  init {
    requireBounded("workflowId", workflowId)
    requireBounded("generationId", generationId)
    requireBounded("findingId", findingId)
    require(evidence.isNotEmpty()) { "Finding disposition evidence must not be empty." }
    evidence.forEachIndexed { index, reference ->
      requireBounded("evidence[$index]", reference)
    }
  }
}

data class GoalSubtaskReviewSummary(
  val currentGenerationId: String?,
  val currentPass: Int,
  val carriedBlockerCount: Int,
  val newBlockerCount: Int,
  val terminalDispositionCounts: Map<String, Int>,
)

private fun requireBounded(name: String, value: String) {
  require(value.isNotBlank()) { "$name must be non-blank." }
  require(value.length <= MAX_REVIEW_REFERENCE_LENGTH) {
    "$name must be at most $MAX_REVIEW_REFERENCE_LENGTH characters."
  }
  require('\n' !in value && '\r' !in value) { "$name must be a single-line bounded value." }
}
