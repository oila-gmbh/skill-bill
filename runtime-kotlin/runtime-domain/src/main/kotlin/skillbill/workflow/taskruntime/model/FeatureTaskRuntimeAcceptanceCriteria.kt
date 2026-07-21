package skillbill.workflow.taskruntime.model

private val CANONICAL_ACCEPTANCE_CRITERION_REF = Regex("AC-[0-9]{3}")

fun canonicalAcceptanceCriterionRef(ordinal: Int): String {
  require(ordinal in 1..MAX_ACCEPTANCE_CRITERION_ORDINAL) {
    "Acceptance criterion ordinal must be 1-based and at most $MAX_ACCEPTANCE_CRITERION_ORDINAL, was $ordinal."
  }
  return "AC-" + ordinal.toString().padStart(ACCEPTANCE_CRITERION_REF_DIGITS, '0')
}

fun acceptanceCriterionRefsFor(criterionCount: Int): List<String> {
  require(criterionCount in 0..MAX_ACCEPTANCE_CRITERION_ORDINAL) {
    "Acceptance criterion count must be between 0 and $MAX_ACCEPTANCE_CRITERION_ORDINAL, was $criterionCount."
  }
  return (1..criterionCount).map(::canonicalAcceptanceCriterionRef)
}

fun acceptanceCriterionOrdinal(criterionRef: String): Int {
  require(CANONICAL_ACCEPTANCE_CRITERION_REF.matches(criterionRef)) {
    "acceptance_criterion_ref '$criterionRef' must use canonical format 'AC-NNN'."
  }
  return criterionRef.removePrefix("AC-").toInt()
}

fun isDeclaredAcceptanceCriterionRef(criterionRef: String, criterionCount: Int): Boolean =
  CANONICAL_ACCEPTANCE_CRITERION_REF.matches(criterionRef) &&
    acceptanceCriterionOrdinal(criterionRef) in 1..criterionCount

private const val ACCEPTANCE_CRITERION_REF_DIGITS: Int = 3
const val MAX_ACCEPTANCE_CRITERION_ORDINAL: Int = 999
