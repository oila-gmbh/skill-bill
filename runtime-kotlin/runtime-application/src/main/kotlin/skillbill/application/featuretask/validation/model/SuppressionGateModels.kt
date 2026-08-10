package skillbill.application.featuretask.validation.model

/** One marker introduction on a scoped path (count is HEAD−base, never below zero). */
data class IntroducedSuppression(
  val path: String,
  val marker: String,
  val introducedCount: Int,
) {
  init {
    require(path.isNotBlank()) { "Introduced suppression path must be non-blank." }
    require(marker.isNotBlank()) { "Introduced suppression marker must be non-blank." }
    require(introducedCount > 0) { "Introduced suppression count must be positive." }
  }
}

/**
 * Runtime-measured suppression delta. [gated] is false when the pack declares no markers
 * (ungated short-circuit). Measurement never reads agent-emitted fields.
 */
data class SuppressionDelta(
  val gated: Boolean,
  val introductions: List<IntroducedSuppression>,
) {
  val totalIntroduced: Int get() = introductions.sumOf { it.introducedCount }
}

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
    private const val MAX_ALLOWED_RATIONALE_NEWLINES: Int = 6

    fun parseAll(raw: Any?): ParseResult = when (raw) {
      null -> ParseResult.Absent
      !is List<*> -> ParseResult.Invalid("suppression_justifications must be an array when present.")
      else -> parseList(raw)
    }

    private fun parseList(list: List<*>): ParseResult {
      if (list.isEmpty()) return ParseResult.Present(emptyList())
      val parsed = ArrayList<SuppressionJustification>(list.size)
      for ((index, entry) in list.withIndex()) {
        val item = parseOne(index, entry)
        if (item.isFailure) {
          return ParseResult.Invalid(item.exceptionOrNull()?.message.orEmpty())
        }
        parsed += item.getOrThrow()
      }
      return ParseResult.Present(parsed)
    }

    private fun parseOne(index: Int, entry: Any?): Result<SuppressionJustification> {
      val map = entry as? Map<*, *>
        ?: return Result.failure(
          IllegalArgumentException("suppression_justifications[$index] must be an object."),
        )
      val path = stringField(map, "path")
      val silenced = stringField(map, "silenced_rule_or_check")
        ?: stringField(map, "silenced_rule")
        ?: stringField(map, "silenced_check")
      val rationale = stringField(map, "rationale")
      if (path == null || silenced == null || rationale == null) {
        val missing = when {
          path == null -> "path"
          silenced == null -> "silenced_rule_or_check"
          else -> "rationale"
        }
        return Result.failure(
          IllegalArgumentException("suppression_justifications[$index].$missing is required."),
        )
      }
      return runCatching {
        SuppressionJustification(
          path = path,
          silencedRuleOrCheck = silenced,
          rationale = rationale,
        )
      }.recoverCatching { error ->
        throw IllegalArgumentException(
          "suppression_justifications[$index] rejected: ${error.message.orEmpty()}",
        )
      }
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
        rationale.count { it == '\n' } > MAX_ALLOWED_RATIONALE_NEWLINES
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
