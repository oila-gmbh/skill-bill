package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.FeatureImplementChildStepCoverageStats

fun buildChildStepCoverageStats(productionRows: List<Map<String, Any?>>): FeatureImplementChildStepCoverageStats {
  val parsedRows = productionRows.map(::parseFeatureImplementChildSteps)
  val runsWithChildSteps = parsedRows.count { it.childSteps.isNotEmpty() }
  return FeatureImplementChildStepCoverageStats(
    runsWithChildSteps = runsWithChildSteps,
    reviewChildStepRuns = parsedRows.count { parsed -> parsed.childSteps.any(::isReviewChildStep) },
    qualityCheckChildStepRuns = parsedRows.count { parsed -> parsed.childSteps.any(::isQualityCheckChildStep) },
    prDescriptionChildStepRuns = parsedRows.count { parsed -> parsed.childSteps.any(::isPrDescriptionChildStep) },
    malformedChildStepRuns = parsedRows.count { it.malformed },
    childStepCoverageRate = rate(runsWithChildSteps, productionRows.size),
  )
}

fun countMalformedChildStepRuns(productionRows: List<Map<String, Any?>>): Int =
  productionRows.count { parseFeatureImplementChildSteps(it).malformed }

private data class ParsedFeatureImplementChildSteps(
  val childSteps: List<Map<String, Any?>>,
  val malformed: Boolean,
)

private fun parseFeatureImplementChildSteps(row: Map<String, Any?>): ParsedFeatureImplementChildSteps {
  val rawChildSteps = row.stringValue("child_steps_json")
  if (rawChildSteps.isBlank()) {
    return ParsedFeatureImplementChildSteps(emptyList(), malformed = false)
  }
  val parsed = JsonSupport.parseArrayOrEmpty(rawChildSteps)
  val malformed = parsed.isEmpty() && rawChildSteps.trim() != "[]"
  return ParsedFeatureImplementChildSteps(
    childSteps = parsed.filterIsInstance<Map<*, *>>().map { it.toStringAnyMap() },
    malformed = malformed || parsed.any { it !is Map<*, *> },
  )
}

private fun isReviewChildStep(childStep: Map<String, Any?>): Boolean {
  val skill = childStep.stringValue("skill")
  return skill.endsWith("code-review") || "-code-review-" in skill
}

private fun isQualityCheckChildStep(childStep: Map<String, Any?>): Boolean =
  childStep.stringValue("skill") == "bill-code-check"

private fun isPrDescriptionChildStep(childStep: Map<String, Any?>): Boolean =
  childStep.stringValue("skill") == "bill-pr-description"

private fun Map<*, *>.toStringAnyMap(): Map<String, Any?> =
  entries.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }.toMap()
