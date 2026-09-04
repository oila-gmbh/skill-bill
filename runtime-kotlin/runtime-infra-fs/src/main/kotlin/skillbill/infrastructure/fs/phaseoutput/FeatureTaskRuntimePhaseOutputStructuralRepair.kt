package skillbill.infrastructure.fs.phaseoutput

import com.fasterxml.jackson.databind.JsonNode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation

/**
 * Strict, bounded syntax repair for phase-output payloads.
 *
 * The engine first parses the original text with duplicate-key detection enabled. Duplicate keys
 * merge object or array values when both sides share a type; otherwise the first value is kept.
 * Delimiter imbalance is repaired separately, and every candidate is parsed again before one can be
 * selected. JSON is the default for flow-shaped payloads; YAML repair is restricted to conservative
 * flow documents so block indentation and plain scalar content are never guessed at.
 */
internal object FeatureTaskRuntimePhaseOutputStructuralRepair {
  private val fencedBlock = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
  private val inlineCodeSpan = Regex("`[^`\\n]*`")

  fun inspect(phaseOutputText: String, sourceLabel: String): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    if (phaseOutputText.isBlank()) {
      return StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
        "Phase output is empty and cannot be parsed as one object.",
      )
    }

    val raw = when (val exact = StrictPhaseOutputParser.parseDocument(phaseOutputText)) {
      is StrictParse.Success -> inspectSuccessfulParse(phaseOutputText, sourceLabel, exact)
      is StrictParse.Failure -> inspectFailedParse(phaseOutputText, sourceLabel, exact)
    }
    return PhaseOutputExpectedShape.alignDecision(raw, sourceLabel, phaseOutputText)
  }

  /**
   * Shared whole-document entry point for other governed YAML contracts. It uses the same strict
   * parser and bounded candidate engine without phase-output envelope extraction, so a manifest's
   * nested objects cannot be mistaken for competing phase envelopes.
   */
  internal fun inspectWholeDocument(
    text: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    if (text.isBlank()) {
      return StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
        "Phase output is empty and cannot be parsed as one object.",
      )
    }
    return when (val exact = StrictPhaseOutputParser.parseDocument(text)) {
      is StrictParse.Success -> if (exact.node.isObject) {
        StructuralRepairDecisions.accepted(text, exact.node, null)
      } else {
        StructuralRepairDecisions.reject(
          FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
          "<root> must be an object.",
        )
      }
      is StrictParse.Failure -> if (exact.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
        StructuralRepairDecisions.reject(
          exact.code,
          "Phase output contains a duplicate key; duplicate keys are never repaired.",
        )
      } else {
        StructuralRepairCandidateEngine.repairExactText(text, sourceLabel)
          ?: StructuralRepairDecisions.reject(exact.code, exact.reason)
      }
    }
  }

  private fun inspectSuccessfulParse(
    phaseOutputText: String,
    sourceLabel: String,
    exact: StrictParse.Success,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    val shouldInspectEmbedded =
      !exact.node.isObject ||
        (!exact.node.has("phase_id") && phaseOutputText.indexOf('{', startIndex = 1) >= 0)
    val embedded = if (shouldInspectEmbedded) selectEmbeddedSafely(phaseOutputText, sourceLabel) else null
    return embedded ?: if (exact.node.isObject) {
      StructuralRepairDecisions.accepted(phaseOutputText, exact.node, null)
    } else {
      StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
        "<root> must be an object.",
      )
    }
  }

  private fun inspectFailedParse(
    phaseOutputText: String,
    sourceLabel: String,
    exact: StrictParse.Failure,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    DuplicateKeyMergeParser.repair(phaseOutputText, sourceLabel)?.let { return it }
    FeatureTaskRuntimePhaseOutputEnvelopeWalker.select(phaseOutputText, sourceLabel)?.let { return it }
    val trimmed = phaseOutputText.trimStart()
    val wholeResponseRepair = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      StructuralRepairCandidateEngine.repairExactText(phaseOutputText, sourceLabel)
    } else {
      null
    }

    // A failed whole-response repair must not hide a valid envelope embedded in prose or a fence.
    // The selected envelope is inspected independently so a malformed embedded envelope carries
    // its own repair evidence instead of being accepted as an extracted, unchanged object.
    val extracted = if (wholeResponseRepair is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted) {
      null
    } else {
      selectEmbeddedSafely(phaseOutputText, sourceLabel)
    }
    return when {
      wholeResponseRepair is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted -> wholeResponseRepair
      extracted != null -> extracted
      wholeResponseRepair != null -> wholeResponseRepair
      else -> StructuralRepairDecisions.reject(exact.code, exact.reason)
    }
  }

  private fun selectEmbeddedSafely(
    text: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? = try {
    selectEmbeddedDocument(text, sourceLabel)?.let { embedded ->
      StructuralRepairDecisions.accepted(embedded.text, embedded.node, embedded.evidence)
    }
  } catch (error: StructuralRepairSelectionException) {
    StructuralRepairDecisions.reject(error.code, error.safeReason)
  }

  private fun selectEmbeddedDocument(text: String, sourceLabel: String): EmbeddedDocument? {
    val candidates = embeddedCandidates(text)
    val parsed = candidates.mapNotNull { candidate ->
      val result = StrictPhaseOutputParser.parseDocument(candidate.text)
      val decision = when (result) {
        is StrictParse.Success -> if (result.node.isObject) {
          StructuralRepairDecisions.accepted(candidate.text, result.node, null)
        } else {
          StructuralRepairDecisions.reject(
            FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
            "<root> must be an object.",
          )
        }
        is StrictParse.Failure ->
          DuplicateKeyMergeParser.repair(
            text = candidate.text,
            sourceLabel = sourceLabel,
            sourceOffset = candidate.sourceOffset,
            sourceText = text,
          ) ?: if (result.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
            null
          } else {
            StructuralRepairCandidateEngine.repairExactText(
              text = candidate.text,
              sourceLabel = sourceLabel,
              sourceOffset = candidate.sourceOffset,
              sourceText = text,
            )
          }
      }
      (decision as? FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted)
        ?.let { EmbeddedDocument(it.text, it.node, it.evidence, candidate.sourceOffset, candidate.sourceEnd) }
    }.filter { it.node.isObject }
    if (parsed.isEmpty()) return null

    val matching = parsed.filter { it.node.path("phase_id").asText("") == sourceLabel }
    val relevant = if (matching.isNotEmpty()) matching else parsed
    val completeShape = relevant.filter { candidate ->
      PhaseOutputExpectedShape.matches(candidate.node, sourceLabel)
    }
    val comparable = if (completeShape.isNotEmpty()) completeShape else relevant
    val distinct = comparable.distinctBy { canonicalNode(it.node) }
    if (distinct.size > 1) {
      throw StructuralRepairSelectionException(
        FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES,
        "Phase output contains multiple conflicting schema candidates.",
      )
    }
    val selected = comparable.firstOrNull() ?: relevant.first()
    val extraCloser = unmatchedClosingOutsideOffset(text, selected.sourceStart, selected.sourceEnd)
      ?: return selected
    val evidence = selected.evidence ?: FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.JSON,
      originalDigest = StructuralRepairSyntax.sha256(text),
      repairedDigest = StructuralRepairSyntax.sha256(selected.text),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      sourceLocation = StructuralRepairSyntax.sourceLocation(sourceLabel, text, extraCloser),
    )
    return selected.copy(evidence = evidence)
  }

  private fun unmatchedClosingOutsideOffset(text: String, sourceStart: Int, sourceEnd: Int): Int? {
    val start = sourceStart.coerceAtLeast(0)
    val end = sourceEnd.coerceAtMost(text.length)
    val outside = text.removeRange(start, end)
    val outsideOffset = StructuralRepairSyntax.scanDelimiters(maskCodeQuoting(outside))
      .unmatchedClosingOffsets.firstOrNull() ?: return null
    return if (outsideOffset < start) outsideOffset else outsideOffset + (end - start)
  }

  private fun maskCodeQuoting(text: String): String {
    val masked = StringBuilder(text)
    fun blank(range: IntRange) = range.forEach { index ->
      if (masked[index] != '\n') masked.setCharAt(index, ' ')
    }
    fencedBlock.findAll(text).forEach { blank(it.range) }
    inlineCodeSpan.findAll(text).forEach { blank(it.range) }
    return masked.toString()
  }

  private fun embeddedCandidates(text: String): List<TextCandidate> = buildList {
    fencedBlock.findAll(text).mapNotNull { match ->
      val group = match.groups[1] ?: return@mapNotNull null
      val trimmed = group.value.trim()
      if (trimmed.isBlank()) return@mapNotNull null
      val sourceOffset = group.range.first + group.value.indexOf(trimmed)
      TextCandidate(trimmed, sourceOffset, sourceOffset + trimmed.length)
    }.toList().asReversed().forEach(::add)
    val open = text.indexOf('{')
    val close = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
    if (open in 0 until close) add(TextCandidate(text.substring(open, close + 1), open, close + 1))
    StructuralRepairSyntax.balancedTopLevelObjectSpans(text).asReversed().forEach { range ->
      add(TextCandidate(text.substring(range), range.first, range.last + 1))
    }
  }.filter { it.text.isNotBlank() }.distinctBy { it.text }

  private fun canonicalNode(node: JsonNode): String = when {
    node.isObject -> node.fieldNames().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { field ->
      "\"$field\":${canonicalNode(node.path(field))}"
    }
    node.isArray -> node.joinToString(prefix = "[", postfix = "]", transform = ::canonicalNode)
    else -> node.toString()
  }

  private class StructuralRepairSelectionException(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val safeReason: String,
  ) : RuntimeException(safeReason)
}
