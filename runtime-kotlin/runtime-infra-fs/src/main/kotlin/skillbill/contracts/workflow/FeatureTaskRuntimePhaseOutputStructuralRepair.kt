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
  internal data class Accepted(
    val text: String,
    val node: JsonNode,
    val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ) : FeatureTaskRuntimePhaseOutputStructuralRepairDecision

  internal data class Rejected(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
    val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ) : FeatureTaskRuntimePhaseOutputStructuralRepairDecision
}

/**
 * Strict, bounded syntax repair for phase-output payloads.
 *
 * The engine first parses the original text with duplicate-key detection enabled. Repair is
 * considered only for delimiter imbalance, and every candidate is parsed again before one can be
 * selected. JSON is the default for flow-shaped payloads; YAML repair is restricted to conservative
 * flow documents so block indentation and plain scalar content are never guessed at.
 */
internal object FeatureTaskRuntimePhaseOutputStructuralRepair {
  private const val MAX_CANDIDATES = 8

  private val strictJsonMapper: ObjectMapper by lazy {
    ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  }

  private val strictYamlMapper: YAMLMapper by lazy {
    YAMLMapper(YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
  }

  private val fencedBlock = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

  fun inspect(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    if (phaseOutputText.isBlank()) {
      return reject(
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
        "Phase output is empty and cannot be parsed as one object.",
      )
    }

    val exact = parseDocument(phaseOutputText)
    if (exact is StrictParse.Success) {
      if (!exact.node.isObject) {
        return reject(
          FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
          "<root> must be an object.",
        )
      }
      if (!exact.node.has("phase_id") && phaseOutputText.indexOf('{', startIndex = 1) >= 0) {
        val embedded = try {
          selectEmbeddedDocument(phaseOutputText, sourceLabel)
        } catch (error: StructuralRepairSelectionException) {
          return reject(error.code, error.safeReason)
        }
        if (embedded != null) return accepted(embedded.text, embedded.node, null)
      }
      return accepted(phaseOutputText, exact.node, null)
    }
    if (exact is StrictParse.Failure && exact.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
      return reject(exact.code, "Phase output contains a duplicate key; duplicate keys are never repaired.")
    }
    val trimmed = phaseOutputText.trimStart()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      val repaired = repairExactText(phaseOutputText, sourceLabel)
      if (repaired != null) return repaired
    }

    // Preserve the existing prose/fence extraction behavior for already-valid agent responses. A
    // delimiter-only suffix is handled above as a repair candidate and is never silently discarded.
    val extracted = try {
      selectEmbeddedDocument(phaseOutputText, sourceLabel)
    } catch (error: StructuralRepairSelectionException) {
      return reject(error.code, error.safeReason)
    }
    if (extracted != null) return accepted(extracted.text, extracted.node, null)

    val failure = exact as? StrictParse.Failure
    return reject(
      failure?.code ?: FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE,
      failure?.reason ?: "Phase output has no bounded structural repair candidate.",
    )
  }

  private fun repairExactText(
    text: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? {
    val formats = formatsFor(text)
    val candidates = mutableListOf<Candidate>()
    var limitExceeded = false
    var unsupportedYaml = false

    formats.forEach { format ->
      val generated = generateCandidates(text, format)
      if (generated.size > MAX_CANDIDATES) {
        limitExceeded = true
      }
      if (format == FeatureTaskRuntimePhaseOutputFormat.YAML && generated.isEmpty() && !isConservativeYamlFlow(text)) {
        unsupportedYaml = true
      }
      generated.take(MAX_CANDIDATES).forEach { candidate ->
        candidates += candidate.copy(format = format)
      }
    }

    if (limitExceeded) {
      return reject(
        FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED,
        "Phase output exceeded the bounded structural-repair candidate limit.",
      )
    }
    val distinctCandidates = candidates.distinctBy { it.format to it.text }
    if (distinctCandidates.isEmpty()) {
      if (unsupportedYaml && formats.singleOrNull() == FeatureTaskRuntimePhaseOutputFormat.YAML) {
        return reject(
          FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR,
          "YAML structural repair is limited to conservative flow structure.",
        )
      }
      return null
    }

    val parseable = distinctCandidates.mapNotNull { candidate ->
      when (val parsed = parseStrict(candidate.text, candidate.format)) {
        is StrictParse.Success -> candidate to parsed
        is StrictParse.Failure -> if (parsed.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
          return reject(parsed.code, "Phase output contains a duplicate key; duplicate keys are never repaired.")
        } else {
          null
        }
      }
    }

    if (parseable.isEmpty()) {
      return reject(
        FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE,
        "Phase output is malformed and no bounded structural-repair candidate parses strictly.",
      )
    }
    if (parseable.size != 1) {
      return reject(
        FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
        "Phase output has multiple strictly parseable structural-repair candidates.",
      )
    }

    val (candidate, parsed) = parseable.single()
    if (!parsed.node.isObject) {
      return reject(
        FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
        "<root> must be an object after structural repair.",
      )
    }
    val operation = if (candidate.text.length < text.length) {
      FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER
    } else {
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER
    }
    val location = sourceLocation(
      sourceLabel,
      text,
      candidate.changedOffset,
    )
    val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = candidate.format,
      originalDigest = sha256(text),
      repairedDigest = sha256(candidate.text),
      operation = operation,
      sourceLocation = location,
    )
    return accepted(candidate.text, parsed.node, evidence)
  }

  private fun selectEmbeddedDocument(text: String, sourceLabel: String): EmbeddedDocument? {
    val candidates = embeddedCandidates(text)
    val parsed = candidates.mapNotNull { candidate ->
      parseDocument(candidate.text).let { result ->
        when (result) {
          is StrictParse.Success -> EmbeddedDocument(candidate.text, result.node)
          is StrictParse.Failure -> null
        }
      }
    }.filter { it.node.isObject }
    if (parsed.isEmpty()) return null

    val matching = parsed.filter { it.node.path("phase_id").asText("") == sourceLabel }
    val relevant = if (matching.isNotEmpty()) matching else parsed
    // Keep the existing final-envelope precedence for a discarded draft that is visibly incomplete,
    // while still rejecting two complete conflicting envelopes before schema normalization.
    val completeShape = relevant.filter { candidate ->
      listOf("contract_version", "phase_id", "status", "summary", "produced_outputs")
        .all { field -> candidate.node.has(field) }
    }
    val comparable = if (completeShape.isNotEmpty()) completeShape else relevant
    val distinct = comparable.distinctBy { canonicalNode(it.node) }
    if (distinct.size > 1) {
      throw StructuralRepairSelectionException(
        FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES,
        "Phase output contains multiple conflicting schema candidates.",
      )
    }
    return comparable.firstOrNull() ?: relevant.first()
  }

  private fun embeddedCandidates(text: String): List<TextCandidate> = buildList {
    fencedBlock.findAll(text).mapNotNull { match ->
      val group = match.groups[1] ?: return@mapNotNull null
      TextCandidate(group.value.trim())
    }.toList().asReversed().forEach(::add)
    balancedTopLevelObjectSpans(text).asReversed().forEach { range ->
      add(TextCandidate(text.substring(range)))
    }
    val open = text.indexOf('{')
    val close = text.lastIndexOf('}')
    if (open in 0 until close) add(TextCandidate(text.substring(open, close + 1)))
  }.filter { it.text.isNotBlank() }.distinctBy { it.text }

  private fun parseDocument(text: String): StrictParse = formatsFor(text).firstNotNullOfOrNull { format ->
    parseStrict(text, format).let { result ->
      when (result) {
        is StrictParse.Success -> result
        is StrictParse.Failure -> if (result.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
          result
        } else {
          null
        }
      }
    }
  } ?: parseStrict(text, formatsFor(text).first())

  private fun parseStrict(text: String, format: FeatureTaskRuntimePhaseOutputFormat): StrictParse = try {
    val mapper = if (format == FeatureTaskRuntimePhaseOutputFormat.JSON) strictJsonMapper else strictYamlMapper
    mapper.factory.createParser(text).use { parser ->
      val node = mapper.readTree<JsonNode>(parser)
      if (parser.nextToken() != null) {
        StrictParse.Failure(
          FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
          "Phase output contains trailing content or multiple documents.",
        )
      } else if (node == null) {
        StrictParse.Failure(
          FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
          "Phase output is empty and cannot be parsed as one object.",
        )
      } else {
        StrictParse.Success(format, node)
      }
    }
  } catch (error: Exception) {
    val duplicate = error.message.orEmpty().contains("duplicate", ignoreCase = true)
    StrictParse.Failure(
      if (duplicate) FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY
      else FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
      if (duplicate) "Phase output contains a duplicate key."
      else "Phase output is malformed and cannot be parsed as one document.",
    )
  }

  private fun formatsFor(text: String): List<FeatureTaskRuntimePhaseOutputFormat> {
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
      containsSingleQuoteOutsideDoubleString(text)

  private fun containsSingleQuoteOutsideDoubleString(text: String): Boolean {
    var inDouble = false
    var escaped = false
    text.forEach { ch ->
      if (inDouble) {
        if (escaped) escaped = false else when (ch) {
          '\\' -> escaped = true
          '"' -> inDouble = false
        }
      } else if (ch == '"') {
        inDouble = true
      } else if (ch == '\'') {
        return true
      }
    }
    return false
  }

  private fun generateCandidates(text: String, format: FeatureTaskRuntimePhaseOutputFormat): List<Candidate> {
    if (format == FeatureTaskRuntimePhaseOutputFormat.YAML && !isConservativeYamlFlow(text)) return emptyList()
    val scan = scanDelimiters(text)
    val candidates = buildList {
      scan.unmatchedClosingOffsets.forEach { offset ->
        add(
          Candidate(
            text = text.removeRange(offset, offset + 1),
            format = format,
            changedOffset = offset,
          ),
        )
      }
      if (scan.openingStack.size == 1) {
        add(
          Candidate(
            text = text + scan.openingStack.single(),
            format = format,
            changedOffset = text.length,
          ),
        )
      }
    }
    return candidates.distinctBy(Candidate::text)
  }

  private fun isConservativeYamlFlow(text: String): Boolean {
    val trimmed = text.trimStart()
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false
    if (text.contains('&') || text.contains('*') || text.contains('!') || text.contains('|') || text.contains('>')) {
      return false
    }
    var inDouble = false
    var inSingle = false
    var escaped = false
    val plain = StringBuilder()
    fun flushPlain(beforeColon: Boolean = false): Boolean {
      val token = plain.toString().trim()
      plain.clear()
      if (token.isEmpty()) return true
      if (beforeColon) return token.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))
      return token.matches(Regex("[-+]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) ||
        token in setOf("true", "false", "null", "~")
    }
    text.forEachIndexed { index, ch ->
      if (inDouble) {
        if (escaped) escaped = false else when (ch) {
          '\\' -> escaped = true
          '"' -> inDouble = false
        }
        return@forEachIndexed
      }
      if (inSingle) {
        if (ch == '\'' && text.getOrNull(index + 1) != '\'') inSingle = false
        return@forEachIndexed
      }
      when (ch) {
        '"' -> {
          if (!flushPlain()) return false
          inDouble = true
        }
        '\'' -> {
          if (!flushPlain()) return false
          inSingle = true
        }
        ':' -> if (!flushPlain(beforeColon = true)) return false
        ',', '{', '}', '[', ']' -> if (!flushPlain()) return false
        else -> if (!ch.isWhitespace()) plain.append(ch)
      }
    }
    return !inDouble && !inSingle && flushPlain()
  }

  private fun scanDelimiters(text: String): DelimiterScan {
    val stack = ArrayDeque<Char>()
    val unmatched = mutableListOf<Int>()
    var inDouble = false
    var inSingle = false
    var escaped = false
    text.forEachIndexed { index, ch ->
      if (inDouble) {
        if (escaped) escaped = false else when (ch) {
          '\\' -> escaped = true
          '"' -> inDouble = false
        }
        return@forEachIndexed
      }
      if (inSingle) {
        if (ch == '\'' && text.getOrNull(index + 1) != '\'') inSingle = false
        return@forEachIndexed
      }
      when (ch) {
        '"' -> inDouble = true
        '\'' -> inSingle = true
        '{' -> stack.addLast('}')
        '[' -> stack.addLast(']')
        '}', ']' -> if (stack.lastOrNull() == ch) stack.removeLast() else unmatched += index
      }
    }
    return DelimiterScan(stack.toList(), unmatched)
  }

  private fun balancedTopLevelObjectSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    text.forEachIndexed { index, ch ->
      if (inString) {
        if (escaped) escaped = false else when (ch) {
          '\\' -> escaped = true
          '"' -> inString = false
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

  private fun canonicalNode(node: JsonNode): String = when {
    node.isObject -> node.fieldNames().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { field ->
      "\"$field\":${canonicalNode(node.path(field))}"
    }
    node.isArray -> node.joinToString(prefix = "[", postfix = "]", transform = ::canonicalNode)
    else -> node.toString()
  }

  private fun sourceLocation(sourceLabel: String, text: String, offset: Int): FeatureTaskRuntimePhaseOutputSourceLocation {
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
    return FeatureTaskRuntimePhaseOutputSourceLocation(sourceLabel.ifBlank { "<unknown>" }, offset, line, column)
  }

  private fun accepted(
    text: String,
    node: JsonNode,
    evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision =
    FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted(text, node, evidence)

  private fun reject(
    code: FeatureTaskRuntimePhaseOutputFailureCode,
    reason: String,
    sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision =
    FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected(code, reason, sourceLocation)

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

  private data class Candidate(
    val text: String,
    val format: FeatureTaskRuntimePhaseOutputFormat,
    val changedOffset: Int,
  )

  private data class DelimiterScan(
    val openingStack: List<Char>,
    val unmatchedClosingOffsets: List<Int>,
  )

  private data class TextCandidate(val text: String)

  private data class EmbeddedDocument(
    val text: String,
    val node: JsonNode,
  )

  private sealed interface StrictParse {
    data class Success(val format: FeatureTaskRuntimePhaseOutputFormat, val node: JsonNode) : StrictParse
    data class Failure(val code: FeatureTaskRuntimePhaseOutputFailureCode, val reason: String) : StrictParse
  }

  private class StructuralRepairSelectionException(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val safeReason: String,
  ) : RuntimeException(safeReason)
}
