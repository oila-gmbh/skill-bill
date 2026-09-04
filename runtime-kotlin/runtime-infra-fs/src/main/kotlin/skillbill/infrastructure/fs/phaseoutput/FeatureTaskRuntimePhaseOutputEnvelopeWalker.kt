package skillbill.infrastructure.fs.phaseoutput

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.salvageCompactReceiptSymbol

internal object FeatureTaskRuntimePhaseOutputEnvelopeWalker {
  /**
   * A complete envelope always decides the response. Only when the text holds none at all is the
   * scan repeated with the absent-`summary` fill enabled.
   *
   * The order is what keeps the fill safe. A phase that emits a summary-less draft and then a
   * corrected envelope must settle on the correction; filling during the first scan would promote
   * the draft to a second valid candidate and turn a recoverable response into a conflict. Two
   * summary-less candidates and nothing complete still conflict, which is the honest answer.
   */
  fun select(text: String, phaseId: String): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? =
    selectMatching(text, phaseId, recoverSummary = false)
      ?: selectMatching(text, phaseId, recoverSummary = true)

  private fun selectMatching(
    text: String,
    phaseId: String,
    recoverSummary: Boolean,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? {
    val matches = linkedMapOf<String, WalkedEnvelope>()
    StructuralRepairSyntax.balancedTopLevelObjectSpans(text).forEach { span ->
      considerSpan(text, span, phaseId, recoverSummary)?.let { envelope ->
        matches[canonical(envelope.node)] = envelope
      }
    }
    if (matches.isEmpty()) return null
    if (matches.size > 1) {
      return StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES,
        "Phase output contains multiple conflicting schema candidates.",
      )
    }
    val selected = matches.values.single()
    val extraCloser = unmatchedCloserOutside(text, selected.sourceStart, selected.sourceEnd)
    val originalSlice = originalSliceForDigest(text, selected, extraCloser)
    val evidence = when {
      selected.spliced || extraCloser != null -> repairEvidence(
        originalSlice,
        selected.envelopeText,
        phaseId,
        FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
        selected.spliceOffset ?: extraCloser ?: selected.sourceStart,
      )
      selected.shapeAligned -> repairEvidence(
        originalSlice,
        selected.envelopeText,
        phaseId,
        FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
        selected.sourceStart,
      )
      else -> null
    }
    return StructuralRepairDecisions.accepted(selected.envelopeText, selected.node, evidence)
  }

  private fun considerSpan(text: String, span: IntRange, phaseId: String, recoverSummary: Boolean): WalkedEnvelope? {
    val summarySource = if (recoverSummary) text.substring(0, span.first) else null
    shapedEnvelope(text.substring(span), span, spliceOffset = null, phaseId, summarySource)?.let {
      return it
    }
    if (!StructuralRepairSyntax.looksLikeObjectFieldContinuation(text, span.last + 1)) return null
    val repaired = text.removeRange(span.last, span.last + 1).substring(span.first)
    return shapedEnvelope(repaired, span, spliceOffset = span.last, phaseId, summarySource)
  }

  // `spliced` is exactly `spliceOffset != null` — the only splice this walker performs records where
  // it cut — so the offset carries both facts and the two can never disagree. `summarySource` is
  // likewise both the switch and the input: null leaves an absent summary fatal to the candidate.
  private fun shapedEnvelope(
    slice: String,
    span: IntRange,
    spliceOffset: Int?,
    phaseId: String,
    summarySource: String?,
  ): WalkedEnvelope? {
    val parsed = parseObject(slice) ?: return null
    val (alignedShape, shapeChanged) = PhaseOutputExpectedShape.align(parsed, phaseId)
    val (aligned, summaryRecovered) = summarySource
      ?.let { PhaseOutputExpectedShape.withRecoveredSummary(alignedShape, phaseId, it) }
      ?: (alignedShape to false)
    val changed = shapeChanged || summaryRecovered
    if (!PhaseOutputExpectedShape.matches(aligned, phaseId)) return null
    val envelopeText = if (changed) PhaseOutputExpectedShape.writeJson(aligned) else slice
    return WalkedEnvelope(
      envelopeText = envelopeText,
      node = aligned,
      sourceStart = span.first,
      sourceEnd = span.last + 1,
      spliced = spliceOffset != null,
      shapeAligned = changed,
      spliceOffset = spliceOffset,
    )
  }

  private fun repairEvidence(
    originalText: String,
    repairedText: String,
    phaseId: String,
    operation: FeatureTaskRuntimePhaseOutputRepairOperation,
    offset: Int,
  ) = FeatureTaskRuntimePhaseOutputRepairEvidence(
    format = FeatureTaskRuntimePhaseOutputFormat.JSON,
    originalDigest = StructuralRepairSyntax.sha256(originalText),
    repairedDigest = StructuralRepairSyntax.sha256(repairedText),
    operation = operation,
    sourceLocation = StructuralRepairSyntax.sourceLocation(phaseId, originalText, offset),
  )

  private fun originalSliceForDigest(text: String, selected: WalkedEnvelope, extraCloser: Int?): String {
    if (selected.spliced || selected.shapeAligned) return text.substring(selected.sourceStart)
    if (extraCloser == null) return text.substring(selected.sourceStart, selected.sourceEnd)
    if (extraCloser < selected.sourceStart) return text
    return text.substring(selected.sourceStart, extraCloser + 1)
  }

  private fun parseObject(slice: String): JsonNode? = when (val parsed = StrictPhaseOutputParser.parseDocument(slice)) {
    is StrictParse.Success -> parsed.node.takeIf { it.isObject }
    is StrictParse.Failure -> null
  }

  private fun canonical(node: JsonNode): String = when {
    node.isObject -> node.fieldNames().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { field ->
      "\"$field\":${canonical(node.path(field))}"
    }
    node.isArray -> node.joinToString(prefix = "[", postfix = "]", transform = ::canonical)
    else -> node.toString()
  }

  private fun unmatchedCloserOutside(text: String, sourceStart: Int, sourceEnd: Int): Int? {
    val start = sourceStart.coerceAtLeast(0)
    val end = sourceEnd.coerceAtMost(text.length)
    val outside = text.removeRange(start, end)
    val outsideOffset = StructuralRepairSyntax.scanDelimiters(outside)
      .unmatchedClosingOffsets.firstOrNull() ?: return null
    return if (outsideOffset < start) outsideOffset else outsideOffset + (end - start)
  }

  private data class WalkedEnvelope(
    val envelopeText: String,
    val node: JsonNode,
    val sourceStart: Int,
    val sourceEnd: Int,
    val spliced: Boolean,
    val shapeAligned: Boolean,
    val spliceOffset: Int?,
  )
}

internal object PhaseOutputExpectedShape {
  private val mapper = ObjectMapper()
  private const val SUMMARY_FIELD = "summary"
  private const val RECOVERED_SUMMARY_MAX_CHARS = 2_000
  private val FENCED_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
  private val FENCE_MARKER_LINE = Regex("(?m)^[ \\t]*```[A-Za-z0-9_-]*[ \\t]*$")
  private val PARAGRAPH_BREAK = Regex("\\r?\\n[ \\t]*\\r?\\n")
  private val WHITESPACE_RUN = Regex("\\s+")

  fun matches(node: JsonNode, phaseId: String): Boolean {
    if (!node.isObject) return false
    if (node.path("phase_id").asText("") != phaseId) return false
    return requiredFields(phaseId).all { field -> node.hasNonNull(field) }
  }

  fun requiredFields(phaseId: String): List<String> = buildList {
    addAll(listOf("contract_version", "phase_id", "status", "summary", "produced_outputs"))
    if (phaseId == "audit") add("verdict")
  }

  /**
   * Every key the envelope declares at its root.
   *
   * The root is closed and `produced_outputs` is open, so a key outside this set is not an unknown
   * envelope field — it is a `produced_outputs` member the producer placed one level too high.
   * `PhaseOutputEnvelopeRootFieldsParityTest` fails if the schema grows a root field this set does
   * not name, which is what keeps a genuinely new envelope field from being demoted as a stray.
   */
  val ENVELOPE_ROOT_FIELDS: Set<String> = setOf(
    "contract_version",
    "phase_id",
    "status",
    "failure_disposition",
    "summary",
    "produced_outputs",
    "derived_notes",
    "verdict",
  )

  fun align(node: JsonNode, phaseId: String): Pair<JsonNode, Boolean> {
    val root = (node as? ObjectNode)?.deepCopy() ?: return node to false
    val produced = root.get("produced_outputs") as? ObjectNode ?: return node to false
    var changed = false
    requiredFields(phaseId).forEach { field ->
      if (!root.hasNonNull(field) && produced.hasNonNull(field)) {
        root.set<JsonNode>(field, produced.get(field))
        produced.remove(field)
        changed = true
      }
    }
    if (demoteStrayRootFields(root, produced)) changed = true
    if (salvageRepairReceiptSymbols(produced)) changed = true
    return root to changed
  }

  /**
   * Moves a key the producer put beside `produced_outputs` into it.
   *
   * The mirror of the nested-required-field case above, and the same judgement: the producer already
   * emitted this value and the shape says where it belongs, so correcting the placement beats
   * discarding a phase's completed work over it. `reconciled_state` on a mutating phase is the
   * common one — the contract calls it an additional report, which reads as a sibling of
   * `produced_outputs` rather than a member of it.
   *
   * A key `produced_outputs` already carries keeps the value it already has: the producer named that
   * member deliberately, and overwriting it would replace a stated value with a guess. The stray
   * root copy still goes, because the closed root is what rejects the envelope.
   */
  private fun demoteStrayRootFields(root: ObjectNode, produced: ObjectNode): Boolean {
    val stray = root.fieldNames().asSequence().filterNot(ENVELOPE_ROOT_FIELDS::contains).toList()
    if (stray.isEmpty()) return false
    stray.forEach { field ->
      if (!produced.has(field)) produced.set<JsonNode>(field, root.get(field))
      root.remove(field)
    }
    return true
  }

  /**
   * Fills an absent `summary` rather than discarding the envelope over it.
   *
   * `summary` is descriptive, never load-bearing: consumers read it as `.orEmpty()`, and the runtime
   * already authors one itself for its own gate-executed phases. Blocking a phase whose entire
   * `produced_outputs` is present and valid, over the one field nothing branches on, spends a
   * session to recover a sentence.
   *
   * The fill prefers the producer's own prose immediately before the envelope, which is where a
   * phase that narrated its work and then emitted JSON actually put its summary — the same recovery
   * the review path performs when it assembles an envelope from prose. Nothing is invented there;
   * the text is the producer's, only its placement was wrong. With no such prose, the marker says
   * plainly that no summary was reported rather than fabricating one.
   *
   * Deliberately narrow: it fires only when `phase_id` matches and every other required field is
   * already present, so an unrelated object never becomes a candidate. That alone is not enough —
   * a summary-less *draft* of the same phase would still qualify — which is why [select] runs this
   * only after a scan without it found no complete envelope at all.
   */
  fun withRecoveredSummary(node: JsonNode, phaseId: String, precedingText: String): Pair<JsonNode, Boolean> {
    val root = (node as? ObjectNode)?.takeIf { onlySummaryIsMissing(it, phaseId) } ?: return node to false
    val recovered = root.deepCopy()
    recovered.put(SUMMARY_FIELD, proseSummary(precedingText) ?: absentSummaryMarker(phaseId))
    return recovered to true
  }

  /** Matching `phase_id` plus every other required field present: the fill can add nothing else. */
  private fun onlySummaryIsMissing(root: ObjectNode, phaseId: String): Boolean =
    root.path("phase_id").asText("") == phaseId &&
      !root.hasNonNull(SUMMARY_FIELD) &&
      requiredFields(phaseId).none { field -> field != SUMMARY_FIELD && !root.hasNonNull(field) }

  private fun absentSummaryMarker(phaseId: String): String =
    "Phase '$phaseId' reported no summary; its produced_outputs carries the phase's output."

  /**
   * The last paragraph before the envelope, fences removed and whitespace collapsed. The last one
   * rather than the first: a phase narrates its work in order, so the paragraph nearest the envelope
   * is the one describing the state the envelope reports.
   */
  private fun proseSummary(precedingText: String): String? = precedingText
    .replace(FENCED_BLOCK, " ")
    // The envelope's own opening fence is unmatched in the text preceding it — its closer sits past
    // the envelope — so it survives the pair strip above and would otherwise be read as the summary.
    .replace(FENCE_MARKER_LINE, "")
    .split(PARAGRAPH_BREAK)
    .lastOrNull(String::isNotBlank)
    ?.replace(WHITESPACE_RUN, " ")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.take(RECOVERED_SUMMARY_MAX_CHARS)

  fun writeJson(node: JsonNode): String = mapper.writeValueAsString(node)

  fun alignDecision(
    decision: FeatureTaskRuntimePhaseOutputStructuralRepairDecision,
    phaseId: String,
    originalText: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    val accepted = decision as? FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted ?: return decision
    val (alignedShape, shapeChanged) = align(accepted.node, phaseId)
    // A whole-document parse succeeded, so there is no prose outside the envelope to recover from:
    // the fill can only be the marker, and the missing sentence still must not cost a session.
    val (aligned, summaryRecovered) = withRecoveredSummary(alignedShape, phaseId, precedingText = "")
    val changed = shapeChanged || summaryRecovered
    if (!changed) return accepted
    val repairedText = writeJson(aligned)
    val evidence = accepted.evidence?.copy(repairedDigest = StructuralRepairSyntax.sha256(repairedText))
      ?: FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = StructuralRepairSyntax.sha256(originalText),
        repairedDigest = StructuralRepairSyntax.sha256(repairedText),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
        sourceLocation = StructuralRepairSyntax.sourceLocation(phaseId, originalText, 0),
      )
    return StructuralRepairDecisions.accepted(repairedText, aligned, evidence)
  }
}

private fun salvageRepairReceiptSymbols(produced: ObjectNode): Boolean {
  val entries = (produced.get("repair_receipt") as? ObjectNode)?.get("entries") as? ArrayNode
    ?: return false
  var changed = false
  for (entryNode in entries) {
    val constructs = (entryNode as? ObjectNode)?.get("constructs") as? ArrayNode ?: continue
    if (salvageConstructSymbols(constructs)) changed = true
  }
  return changed
}

private fun salvageConstructSymbols(constructs: ArrayNode): Boolean {
  var changed = false
  for (constructNode in constructs) {
    val construct = constructNode as? ObjectNode ?: continue
    if (salvageConstructSymbol(construct)) changed = true
  }
  return changed
}

private fun salvageConstructSymbol(construct: ObjectNode): Boolean {
  val symbolNode = construct.get("symbol")?.takeIf { it.isTextual } ?: return false
  val salvaged = salvageCompactReceiptSymbol(symbolNode.asText()) ?: return false
  construct.put("symbol", salvaged)
  return true
}
