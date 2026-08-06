package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProviderLimitSignal

/**
 * Separates "the provider refused the request at a usage or rate limit" from every other non-zero
 * agent exit.
 *
 * The distinction is load-bearing. A limit refusal produces no phase output, is not repairable by
 * re-prompting, and clears on the provider's own clock, so charging it to a repair budget spends
 * that budget on attempts that cannot succeed and then reports the phase as having produced invalid
 * output — which it never did. A recognized limit pauses the run instead.
 *
 * Only the tail of a failed launch is inspected, and only for phrases a CLI emits when refusing:
 * scanning the whole stream would let a phase whose legitimate output discusses rate limiting
 * classify itself as limited.
 */
object FeatureTaskRuntimeProviderLimitDetector {
  const val INSPECTED_TAIL_CHARS: Int = 2000
  private const val EVIDENCE_MAX_CHARS = 200

  // Anchored on the refusal itself, never on a bare number or a generic "limit": the words below are
  // what a provider CLI prints when it declines the work, not what a transcript might mention.
  private val SIGNATURES: List<Regex> = listOf(
    """hit your [a-z0-9 -]{0,24}limit""",
    """reached your [a-z0-9 -]{0,24}limit""",
    """usage limit reached""",
    """rate[ _-]?limit(?:_error| exceeded|ed)""",
    """too many requests""",
    """(?:status|code|http)\D{0,10}429""",
    """quota exceeded""",
    """insufficient_quota""",
  ).map { Regex(it, RegexOption.IGNORE_CASE) }

  private val RESET_HINT = Regex("""reset[s]?(?:\s+(?:at|on|in))?\s+([^\n]{1,60})""", RegexOption.IGNORE_CASE)

  /**
   * Returns the signal for the first inspected [outputs] stream whose tail carries a signature, else
   * null. Streams are inspected in the order given, so a caller passes stderr before stdout.
   */
  fun detect(vararg outputs: String): FeatureTaskRuntimeProviderLimitSignal? =
    outputs.firstNotNullOfOrNull(::detectInTail)

  private fun detectInTail(output: String): FeatureTaskRuntimeProviderLimitSignal? {
    val tail = output.takeLast(INSPECTED_TAIL_CHARS)
    val line = tail.lineSequence()
      .map(String::trim)
      .firstOrNull { candidate -> SIGNATURES.any { it.containsMatchIn(candidate) } }
      ?: return null
    return FeatureTaskRuntimeProviderLimitSignal(
      evidence = line.take(EVIDENCE_MAX_CHARS),
      resetHint = RESET_HINT.find(line)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',')?.takeIf(String::isNotBlank),
    )
  }
}
