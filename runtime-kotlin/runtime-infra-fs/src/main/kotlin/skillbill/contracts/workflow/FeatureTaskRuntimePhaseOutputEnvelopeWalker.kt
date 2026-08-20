package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation

internal object FeatureTaskRuntimePhaseOutputEnvelopeWalker {
  fun select(text: String, phaseId: String): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? {
    val matches = linkedMapOf<String, WalkedEnvelope>()
    StructuralRepairSyntax.balancedTopLevelObjectSpans(text).forEach { span ->
      considerSpan(text, span, phaseId)?.let { envelope ->
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

  private fun considerSpan(text: String, span: IntRange, phaseId: String): WalkedEnvelope? {
    shapedEnvelope(text.substring(span), span, spliced = false, spliceOffset = null, phaseId)?.let {
      return it
    }
    if (!StructuralRepairSyntax.looksLikeObjectFieldContinuation(text, span.last + 1)) return null
    val repaired = text.removeRange(span.last, span.last + 1).substring(span.first)
    return shapedEnvelope(repaired, span, spliced = true, spliceOffset = span.last, phaseId)
  }

  private fun shapedEnvelope(
    slice: String,
    span: IntRange,
    spliced: Boolean,
    spliceOffset: Int?,
    phaseId: String,
  ): WalkedEnvelope? {
    val parsed = parseObject(slice) ?: return null
    val (aligned, changed) = PhaseOutputExpectedShape.align(parsed, phaseId)
    if (!PhaseOutputExpectedShape.matches(aligned, phaseId)) return null
    val envelopeText = if (changed) PhaseOutputExpectedShape.writeJson(aligned) else slice
    return WalkedEnvelope(
      envelopeText = envelopeText,
      node = aligned,
      sourceStart = span.first,
      sourceEnd = span.last + 1,
      spliced = spliced,
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

  fun matches(node: JsonNode, phaseId: String): Boolean {
    if (!node.isObject) return false
    if (node.path("phase_id").asText("") != phaseId) return false
    return requiredFields(phaseId).all { field -> node.hasNonNull(field) }
  }

  fun requiredFields(phaseId: String): List<String> = buildList {
    addAll(listOf("contract_version", "phase_id", "status", "summary", "produced_outputs"))
    if (phaseId == "audit") add("verdict")
  }

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
    return root to changed
  }

  fun writeJson(node: JsonNode): String = mapper.writeValueAsString(node)

  fun alignDecision(
    decision: FeatureTaskRuntimePhaseOutputStructuralRepairDecision,
    phaseId: String,
    originalText: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    val accepted = decision as? FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted ?: return decision
    val (aligned, changed) = align(accepted.node, phaseId)
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
