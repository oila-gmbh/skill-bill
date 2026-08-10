package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.SuppressionDelta
import skillbill.application.featuretask.validation.model.SuppressionGateDecision
import skillbill.application.featuretask.validation.model.SuppressionJustification

object SuppressionJustificationGate {
  fun evaluate(delta: SuppressionDelta, justifications: List<SuppressionJustification>): SuppressionGateDecision {
    if (!delta.gated || delta.totalIntroduced == 0) {
      return SuppressionGateDecision.Allow()
    }
    if (justifications.isEmpty()) {
      return SuppressionGateDecision.Block(absentJustificationReason(delta))
    }
    val remaining = delta.introductions.associate { intro ->
      (intro.path to intro.marker) to intro.introducedCount
    }.toMutableMap()
    for (justification in justifications) {
      val key = remaining.keys.firstOrNull { (path, _) -> path == justification.path } ?: continue
      val left = remaining.getValue(key) - 1
      if (left <= 0) remaining.remove(key) else remaining[key] = left
    }
    if (remaining.isEmpty()) {
      return SuppressionGateDecision.Allow(justifications)
    }
    return SuppressionGateDecision.Block(underReportedReason(remaining))
  }

  private fun absentJustificationReason(delta: SuppressionDelta): String {
    val paths = delta.introductions.map { it.path }.distinct()
    val markers = delta.introductions.map { it.marker }.distinct()
    return "Validation blocked: introduced suppression markers require justification. " +
      "offending_paths=${paths.joinToString(",")} " +
      "unaccounted_markers=${markers.joinToString(",")} " +
      "introduced_count=${delta.totalIntroduced}."
  }

  private fun underReportedReason(remaining: Map<Pair<String, String>, Int>): String {
    val paths = remaining.keys.map { it.first }.distinct()
    val markers = remaining.keys.map { it.second }.distinct()
    val count = remaining.values.sum()
    return "Validation blocked: suppression justification under-reports the measured delta. " +
      "offending_paths=${paths.joinToString(",")} " +
      "unaccounted_markers=${markers.joinToString(",")} " +
      "unaccounted_count=$count."
  }
}
