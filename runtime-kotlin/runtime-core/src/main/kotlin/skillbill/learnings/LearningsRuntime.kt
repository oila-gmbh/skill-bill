package skillbill.learnings

data class RejectedLearningSourceOutcome(
  val eventType: String,
  val note: String,
)

data class LearningSourceReference(
  val reviewRunId: String,
  val findingId: String,
)

data class LearningSourceValidation(
  val reviewRunId: String,
  val findingId: String,
  val rejectedOutcome: RejectedLearningSourceOutcome,
)

object LearningsRuntime {
  val learningStatuses: List<String> = listOf("active", "disabled")
  val rejectedFindingOutcomeTypes: List<String> = listOf("fix_rejected", "false_positive")

  fun validateLearningScope(scope: LearningScope, scopeKey: String): Pair<LearningScope, String> =
    scope to scope.normalizeScopeKey(scopeKey)

  fun validateLearningSourceReference(sourceReviewRunId: String?, sourceFindingId: String?): LearningSourceReference {
    require(!sourceReviewRunId.isNullOrBlank() && !sourceFindingId.isNullOrBlank()) {
      "Learnings must be derived from a rejected review finding. Provide both --from-run and --from-finding."
    }
    return LearningSourceReference(
      reviewRunId = sourceReviewRunId.trim(),
      findingId = sourceFindingId.trim(),
    )
  }

  fun validateLearningSource(
    sourceReviewRunId: String?,
    sourceFindingId: String?,
    sourceFindingExists: Boolean,
    latestRejectedOutcome: RejectedLearningSourceOutcome?,
  ): LearningSourceValidation = validateLearningSource(
    sourceReference = validateLearningSourceReference(sourceReviewRunId, sourceFindingId),
    sourceFindingExists = sourceFindingExists,
    latestRejectedOutcome = latestRejectedOutcome,
  )

  fun validateLearningSource(
    sourceReference: LearningSourceReference,
    sourceFindingExists: Boolean,
    latestRejectedOutcome: RejectedLearningSourceOutcome?,
  ): LearningSourceValidation {
    require(sourceFindingExists) {
      "Unknown learning source '${sourceReference.reviewRunId}:${sourceReference.findingId}'. " +
        "Import the review and finding first."
    }
    require(latestRejectedOutcome != null) {
      "Finding '${sourceReference.findingId}' in run '${sourceReference.reviewRunId}' has no rejected outcome. " +
        "Learnings can only be created from findings the user rejected " +
        "(fix_rejected or false_positive)."
    }
    return LearningSourceValidation(
      reviewRunId = sourceReference.reviewRunId,
      findingId = sourceReference.findingId,
      rejectedOutcome = latestRejectedOutcome,
    )
  }

  fun normalizeOptionalLookupValue(rawValue: String?, argumentName: String): String? {
    if (rawValue == null) {
      return null
    }
    val normalized = rawValue.trim()
    require(normalized.isNotEmpty()) { "$argumentName must not be empty when provided." }
    return normalized
  }

  fun validateLearningStatus(status: String): String {
    require(status in learningStatuses) {
      "Learning status must be one of ${learningStatuses.joinToString(", ")}."
    }
    return status
  }

  fun validateLearningText(title: String, ruleText: String): Pair<String, String> {
    val normalizedTitle = title.trim()
    val normalizedRuleText = ruleText.trim()
    require(normalizedTitle.isNotEmpty()) { "Learning title must not be empty." }
    require(normalizedRuleText.isNotEmpty()) { "Learning rule text must not be empty." }
    return normalizedTitle to normalizedRuleText
  }

  fun effectiveRationale(rationale: String, rejectedOutcomeNote: String): String =
    if (rationale.trim().isEmpty() && rejectedOutcomeNote.isNotBlank()) {
      rejectedOutcomeNote.trim()
    } else {
      rationale.trim()
    }
}
