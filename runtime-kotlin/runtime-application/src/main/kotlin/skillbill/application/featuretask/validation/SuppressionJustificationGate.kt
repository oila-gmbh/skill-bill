package skillbill.application.featuretask.validation

/**
 * Per-suppression justification carried on `validation_result` when the measured delta is
 * non-zero. Bounded projection: short rationale only — never raw command output, transcripts,
 * or telemetry.
 */
data class SuppressionJustification(
  val path: String,
  val silencedRuleOrCheck: String,
  val rationale: String,
) {
  init {
    require(path.isNotBlank()) { "suppression justification path must be non-blank." }
    require(silencedRuleOrCheck.isNotBlank()) {
      "suppression justification silenced_rule_or_check must be non-blank."
    }
    require(rationale.isNotBlank()) { "suppression justification rationale must be non-blank." }
    require(rationale.length <= MAX_RATIONALE_CHARS) {
      "suppression justification rationale exceeds $MAX_RATIONALE_CHARS characters."
    }
    require(!looksLikeForbiddenPayload(rationale)) {
      "suppression justification rationale must not carry raw command output, transcripts, or telemetry."
    }
  }

  fun toArtifactMap(): Map<String, String> = linkedMapOf(
    "path" to path,
    "silenced_rule_or_check" to silencedRuleOrCheck,
    "rationale" to rationale,
  )

  companion object {
    const val MAX_RATIONALE_CHARS: Int = 512

    fun parseAll(raw: Any?): ParseResult {
      if (raw == null) return ParseResult.Absent
      val list = raw as? List<*>
        ?: return ParseResult.Invalid("suppression_justifications must be an array when present.")
      if (list.isEmpty()) return ParseResult.Present(emptyList())
      val parsed = ArrayList<SuppressionJustification>(list.size)
      list.forEachIndexed { index, entry ->
        val map = entry as? Map<*, *>
          ?: return ParseResult.Invalid("suppression_justifications[$index] must be an object.")
        val path = stringField(map, "path")
          ?: return ParseResult.Invalid("suppression_justifications[$index].path is required.")
        val silenced = stringField(map, "silenced_rule_or_check")
          ?: stringField(map, "silenced_rule")
          ?: stringField(map, "silenced_check")
          ?: return ParseResult.Invalid(
            "suppression_justifications[$index].silenced_rule_or_check is required.",
          )
        val rationale = stringField(map, "rationale")
          ?: return ParseResult.Invalid("suppression_justifications[$index].rationale is required.")
        runCatching {
          parsed += SuppressionJustification(
            path = path,
            silencedRuleOrCheck = silenced,
            rationale = rationale,
          )
        }.onFailure { error ->
          return ParseResult.Invalid(
            "suppression_justifications[$index] rejected: ${error.message.orEmpty()}",
          )
        }
      }
      return ParseResult.Present(parsed)
    }

    private fun stringField(map: Map<*, *>, key: String): String? =
      (map[key] as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun looksLikeForbiddenPayload(rationale: String): Boolean {
      val lower = rationale.lowercase()
      return "build successful" in lower ||
        "passed (" in lower ||
        lower.contains("traceback (most recent call last)") ||
        lower.contains("\"telemetry\"") ||
        lower.contains("session_id=") ||
        rationale.count { it == '\n' } > 6
    }
  }

  sealed interface ParseResult {
    data object Absent : ParseResult
    data class Present(val values: List<SuppressionJustification>) : ParseResult
    data class Invalid(val reason: String) : ParseResult
  }
}

sealed interface SuppressionGateDecision {
  data class Allow(val justifications: List<SuppressionJustification> = emptyList()) : SuppressionGateDecision

  data class Block(val reason: String) : SuppressionGateDecision
}

object SuppressionJustificationGate {
  fun evaluate(
    delta: SuppressionDelta,
    justifications: List<SuppressionJustification>,
  ): SuppressionGateDecision {
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
