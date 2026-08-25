@file:Suppress("TooGenericExceptionCaught", "LongMethod")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.security.MessageDigest

/** Internal parse result; Jackson nodes never cross the domain port. */
internal sealed interface FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
  data class Accepted(
    val text: String,
    val node: JsonNode,
    val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ) : FeatureTaskRuntimePhaseOutputStructuralRepairDecision

  data class Rejected(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
    val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ) : FeatureTaskRuntimePhaseOutputStructuralRepairDecision
}

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

internal object StrictPhaseOutputParser {
  private val strictJsonMapper: ObjectMapper by lazy {
    ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  }

  private val strictYamlMapper: YAMLMapper by lazy {
    YAMLMapper(YAMLFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) })
  }

  fun parseDocument(text: String): StrictParse = formatsFor(text).firstNotNullOfOrNull { format ->
    parseStrict(text, format).let { result ->
      when (result) {
        is StrictParse.Success -> result
        is StrictParse.Failure -> result.takeIf {
          it.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY
        }
      }
    }
  } ?: parseStrict(text, formatsFor(text).first())

  fun parseStrict(text: String, format: FeatureTaskRuntimePhaseOutputFormat): StrictParse = try {
    val mapper = if (format == FeatureTaskRuntimePhaseOutputFormat.JSON) {
      strictJsonMapper
    } else {
      strictYamlMapper
    }
    mapper.factory.createParser(text).use { parser ->
      val node = mapper.readTree<JsonNode>(parser)
      when {
        parser.nextToken() != null -> StrictParse.Failure(
          FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
          "Phase output contains trailing content or multiple documents.",
        )
        node == null -> StrictParse.Failure(
          FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
          "Phase output is empty and cannot be parsed as one object.",
        )
        else -> StrictParse.Success(format, node)
      }
    }
  } catch (error: Exception) {
    val duplicate = DUPLICATE_FIELD_OR_KEY.containsMatchIn(error.message.orEmpty())
    StrictParse.Failure(
      if (duplicate) {
        FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY
      } else {
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED
      },
      if (duplicate) {
        "Phase output contains a duplicate key."
      } else {
        "Phase output is malformed and cannot be parsed as one document."
      },
    )
  }

  private val DUPLICATE_FIELD_OR_KEY = Regex("(?i)duplicate (field|key)\\b")

  fun formatsFor(text: String): List<FeatureTaskRuntimePhaseOutputFormat> {
    val first = text.firstOrNull { !it.isWhitespace() }
    return if (first == '{' || first == '[') {
      if (looksLikeYamlFlow(text)) {
        listOf(FeatureTaskRuntimePhaseOutputFormat.JSON, FeatureTaskRuntimePhaseOutputFormat.YAML)
      } else {
        listOf(FeatureTaskRuntimePhaseOutputFormat.JSON)
      }
    } else {
      listOf(FeatureTaskRuntimePhaseOutputFormat.YAML)
    }
  }

  private fun looksLikeYamlFlow(text: String): Boolean =
    Regex("(?s)^\\s*\\{\\s*[A-Za-z_][A-Za-z0-9_-]*\\s*:").containsMatchIn(text) ||
      Regex("""(?s)^\s*\{\s*"[^"]*"\s*:[^{}]*,\s*[A-Za-z_][A-Za-z0-9_-]*\s*:""").containsMatchIn(text) ||
      containsSingleQuoteOutsideDoubleString(text)

  private fun containsSingleQuoteOutsideDoubleString(text: String): Boolean {
    var inDouble = false
    var escaped = false
    text.forEach { ch ->
      if (inDouble) {
        if (escaped) {
          escaped = false
        } else {
          when (ch) {
            '\\' -> escaped = true
            '"' -> inDouble = false
          }
        }
      } else if (ch == '"') {
        inDouble = true
      } else if (ch == '\'') {
        return true
      }
    }
    return false
  }
}

internal object StructuralRepairCandidateEngine {
  private const val MAX_CANDIDATES = 8

  fun repairExactText(
    text: String,
    sourceLabel: String,
    sourceOffset: Int = 0,
    sourceText: String = text,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? {
    val generation = collectCandidates(text)
    return when {
      generation.limitExceeded -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED,
        "Phase output exceeded the bounded structural-repair candidate limit.",
      )
      generation.candidates.isNotEmpty() -> evaluateCandidates(
        generation.candidates,
        text,
        sourceLabel,
        sourceOffset,
        sourceText,
      )
      generation.unsupportedYaml -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR,
        "YAML structural repair is limited to conservative flow structure.",
      )
      else -> null
    }
  }

  private fun collectCandidates(text: String): CandidateGeneration {
    val formats = StrictPhaseOutputParser.formatsFor(text)
    val generatedByFormat = formats.associateWith { format ->
      StructuralRepairSyntax.generateCandidates(text, format, MAX_CANDIDATES)
    }
    val candidates = generatedByFormat.flatMap { (format, generated) ->
      generated.take(MAX_CANDIDATES).map { candidate -> candidate.copy(format = format) }
    }.distinctBy { it.format to it.text }
    val unsupportedYaml =
      formats.singleOrNull() == FeatureTaskRuntimePhaseOutputFormat.YAML &&
        generatedByFormat[FeatureTaskRuntimePhaseOutputFormat.YAML].isNullOrEmpty() &&
        !StructuralRepairSyntax.isConservativeYamlFlow(text)
    return CandidateGeneration(
      candidates = candidates,
      limitExceeded = formats.any { format ->
        StructuralRepairSyntax.exceedsCandidateLimit(text, format, MAX_CANDIDATES)
      } || generatedByFormat.values.any { it.size > MAX_CANDIDATES },
      unsupportedYaml = unsupportedYaml,
    )
  }

  internal fun evaluateCandidates(
    candidates: List<Candidate>,
    originalText: String,
    sourceLabel: String,
    sourceOffset: Int,
    sourceText: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    val considered = candidates.mapNotNull { candidate ->
      when (val result = StrictPhaseOutputParser.parseStrict(candidate.text, candidate.format)) {
        is StrictParse.Success -> Triple(candidate, result.node, false)
        is StrictParse.Failure -> if (result.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
          DuplicateKeyMergeParser.merge(candidate.text, candidate.format)?.let { merged ->
            Triple(
              candidate.copy(text = merged.repairedText, changedOffset = merged.firstDuplicateOffset),
              merged.node,
              true,
            )
          }
        } else {
          null
        }
      }
    }
    return when {
      considered.isEmpty() -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE,
        "Phase output is malformed and no bounded structural-repair candidate parses strictly.",
      )
      considered.size != 1 -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
        "Phase output has multiple strictly parseable structural-repair candidates.",
      )
      else -> {
        val (candidate, node, mergedDuplicateKeys) = considered.single()
        acceptCandidate(
          candidate,
          node,
          mergedDuplicateKeys,
          StructuralRepairOrigin(originalText, sourceLabel, sourceOffset, sourceText),
        )
      }
    }
  }

  private data class StructuralRepairOrigin(
    val originalText: String,
    val sourceLabel: String,
    val sourceOffset: Int,
    val sourceText: String,
  )

  private fun acceptCandidate(
    candidate: Candidate,
    node: JsonNode,
    mergedDuplicateKeys: Boolean,
    origin: StructuralRepairOrigin,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    return if (!node.isObject) {
      StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
        "<root> must be an object after structural repair.",
      )
    } else {
      val operation = when {
        mergedDuplicateKeys -> FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS
        candidate.text.length < origin.originalText.length ->
          FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER
        else -> FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER
      }
      val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = if (mergedDuplicateKeys) FeatureTaskRuntimePhaseOutputFormat.JSON else candidate.format,
        originalDigest = StructuralRepairSyntax.sha256(origin.originalText),
        repairedDigest = StructuralRepairSyntax.sha256(candidate.text),
        operation = operation,
        sourceLocation = StructuralRepairSyntax.sourceLocation(
          origin.sourceLabel,
          origin.sourceText,
          origin.sourceOffset + candidate.changedOffset,
        ),
      )
      StructuralRepairDecisions.accepted(candidate.text, node, evidence)
    }
  }
}

internal object StructuralRepairSyntax {
  fun generateCandidates(
    text: String,
    format: FeatureTaskRuntimePhaseOutputFormat,
    maxCandidates: Int,
  ): List<Candidate> {
    if (format == FeatureTaskRuntimePhaseOutputFormat.YAML && !isConservativeYamlFlow(text)) {
      return emptyList()
    }
    val scan = scanDelimiters(text)
    val candidates = buildList {
      scan.unmatchedClosingOffsets.asSequence().take(maxCandidates + 1).forEach { offset ->
        add(Candidate(text.removeRange(offset, offset + 1), format, offset))
      }
      scan.firstMismatchedClosing?.let { mismatch ->
        mismatch.missingCloser?.let { missingCloser ->
          add(
            Candidate(
              text.substring(0, mismatch.offset) + missingCloser + text.substring(mismatch.offset),
              format,
              mismatch.offset,
            ),
          )
        }
      }
      if (scan.openingStack.size == 1) {
        add(Candidate(text + scan.openingStack.single(), format, text.length))
      }
      balancedTopLevelObjectSpans(text).forEach { span ->
        if (looksLikeObjectFieldContinuation(text, span.last + 1)) {
          add(Candidate(text.removeRange(span.last, span.last + 1), format, span.last))
        }
      }
    }
    return candidates.distinctBy(Candidate::text)
  }

  fun exceedsCandidateLimit(text: String, format: FeatureTaskRuntimePhaseOutputFormat, maxCandidates: Int): Boolean {
    if (format == FeatureTaskRuntimePhaseOutputFormat.YAML && !isConservativeYamlFlow(text)) return false
    val scan = scanDelimiters(text)
    val candidateCount = scan.unmatchedClosingOffsets.size +
      (if (scan.firstMismatchedClosing?.missingCloser != null) 1 else 0) +
      (if (scan.openingStack.size == 1) 1 else 0) +
      balancedTopLevelObjectSpans(text).count { span -> looksLikeObjectFieldContinuation(text, span.last + 1) }
    return candidateCount > maxCandidates
  }

  fun looksLikeObjectFieldContinuation(text: String, offset: Int): Boolean {
    var index = offset
    while (index < text.length && text[index].isWhitespace()) index++
    if (index >= text.length || text[index] != ',') return false
    index++
    while (index < text.length && text[index].isWhitespace()) index++
    return index < text.length && (text[index] == '"' || text[index].isLetter())
  }

  fun isConservativeYamlFlow(text: String): Boolean {
    val trimmed = text.trimStart()
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false
    if (listOf('&', '*', '!', '|', '>').any(text::contains)) return false
    return YamlFlowScalarValidator(text).validate()
  }

  fun scanDelimiters(text: String): DelimiterScan = DelimiterScanner(text).scan()

  fun balancedTopLevelObjectSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    text.forEachIndexed { index, ch ->
      if (inString) {
        if (escaped) {
          escaped = false
        } else {
          when (ch) {
            '\\' -> escaped = true
            '"' -> inString = false
          }
        }
        return@forEachIndexed
      }
      when (ch) {
        '"' -> inString = true
        '{' -> {
          if (depth == 0) start = index
          depth += 1
        }
        '}' -> if (depth > 0) {
          depth -= 1
          if (depth == 0 && start >= 0) {
            spans += start..index
            start = -1
          }
        }
      }
    }
    return spans
  }

  fun sourceLocation(sourceLabel: String, text: String, offset: Int): FeatureTaskRuntimePhaseOutputSourceLocation {
    var line = 1
    var column = 1
    text.take(offset.coerceIn(0, text.length)).forEach { ch ->
      if (ch == '\n') {
        line += 1
        column = 1
      } else {
        column += 1
      }
    }
    return FeatureTaskRuntimePhaseOutputSourceLocation(
      sourceLabel.ifBlank { "<unknown>" },
      offset,
      line,
      column,
    )
  }

  fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
}

internal object StructuralRepairDecisions {
  fun accepted(
    text: String,
    node: JsonNode,
    evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision =
    FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted(text, node, evidence)

  fun reject(
    code: FeatureTaskRuntimePhaseOutputFailureCode,
    reason: String,
    sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision =
    FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected(code, reason, sourceLocation)
}

private class YamlFlowScalarValidator(private val text: String) {
  private var inDouble = false
  private var inSingle = false
  private var escaped = false
  private val plain = StringBuilder()

  fun validate(): Boolean {
    text.forEachIndexed { index, ch ->
      if (!consume(index, ch)) return false
    }
    return !inDouble && !inSingle && flushPlain()
  }

  private fun consume(index: Int, ch: Char): Boolean = when {
    inDouble -> {
      consumeDoubleQuoted(ch)
      true
    }
    inSingle -> {
      if (ch == '\'' && text.getOrNull(index + 1) != '\'') inSingle = false
      true
    }
    else -> consumeUnquoted(ch)
  }

  private fun consumeDoubleQuoted(ch: Char) {
    if (escaped) {
      escaped = false
    } else {
      when (ch) {
        '\\' -> escaped = true
        '"' -> inDouble = false
      }
    }
  }

  private fun consumeUnquoted(ch: Char): Boolean = when (ch) {
    '"' -> flushPlain().also { valid -> if (valid) inDouble = true }
    '\'' -> flushPlain().also { valid -> if (valid) inSingle = true }
    ':' -> flushPlain(beforeColon = true)
    ',', '{', '}', '[', ']' -> flushPlain()
    else -> true.also { if (!ch.isWhitespace()) plain.append(ch) }
  }

  private fun flushPlain(beforeColon: Boolean = false): Boolean {
    val token = plain.toString().trim()
    plain.clear()
    return when {
      token.isEmpty() -> true
      beforeColon -> token.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))
      else -> token.matches(Regex("[-+]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) ||
        token in setOf("true", "false", "null", "~")
    }
  }
}

private class DelimiterScanner(private val text: String) {
  private val stack = ArrayDeque<Char>()
  private val unmatched = mutableListOf<Int>()
  private var firstMismatch: MismatchedClosing? = null
  private var inDouble = false
  private var inSingle = false
  private var escaped = false

  fun scan(): DelimiterScan {
    text.forEachIndexed(::consume)
    return DelimiterScan(stack.toList(), unmatched, firstMismatch)
  }

  private fun consume(index: Int, ch: Char) {
    when {
      inDouble -> consumeDoubleQuoted(ch)
      inSingle -> if (ch == '\'' && text.getOrNull(index + 1) != '\'') inSingle = false
      else -> consumeStructural(index, ch)
    }
  }

  private fun consumeDoubleQuoted(ch: Char) {
    if (escaped) {
      escaped = false
    } else {
      when (ch) {
        '\\' -> escaped = true
        '"' -> inDouble = false
      }
    }
  }

  private fun consumeStructural(index: Int, ch: Char) {
    when (ch) {
      '"' -> inDouble = true
      '\'' -> inSingle = true
      '{' -> stack.addLast('}')
      '[' -> stack.addLast(']')
      '}', ']' -> consumeClosing(index, ch)
    }
  }

  private fun consumeClosing(index: Int, ch: Char) {
    if (stack.lastOrNull() == ch) {
      stack.removeLast()
    } else {
      unmatched += index
      if (firstMismatch == null) {
        val missingCloser = stack.lastOrNull()
        val enclosingCloser = stack.elementAtOrNull(stack.size - 2)
        firstMismatch = MismatchedClosing(
          offset = index,
          missingCloser = missingCloser.takeIf { it != null && enclosingCloser == ch },
        )
      }
    }
  }
}

private data class CandidateGeneration(
  val candidates: List<Candidate>,
  val limitExceeded: Boolean,
  val unsupportedYaml: Boolean,
)

internal data class Candidate(
  val text: String,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val changedOffset: Int,
)

internal data class DelimiterScan(
  val openingStack: List<Char>,
  val unmatchedClosingOffsets: List<Int>,
  val firstMismatchedClosing: MismatchedClosing?,
)

internal data class MismatchedClosing(
  val offset: Int,
  val missingCloser: Char?,
)

private data class TextCandidate(
  val text: String,
  val sourceOffset: Int,
  val sourceEnd: Int,
)

private data class EmbeddedDocument(
  val text: String,
  val node: JsonNode,
  val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val sourceStart: Int,
  val sourceEnd: Int,
)

internal sealed interface StrictParse {
  data class Success(
    val format: FeatureTaskRuntimePhaseOutputFormat,
    val node: JsonNode,
  ) : StrictParse

  data class Failure(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
  ) : StrictParse
}
