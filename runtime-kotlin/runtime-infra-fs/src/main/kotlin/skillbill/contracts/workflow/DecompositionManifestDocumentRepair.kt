@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.security.MessageDigest

internal sealed interface DecompositionManifestDocumentRepairDecision {
  data class Accepted(
    val text: String,
    val node: JsonNode,
    val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ) : DecompositionManifestDocumentRepairDecision

  data class Rejected(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
    val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ) : DecompositionManifestDocumentRepairDecision
}

internal object DecompositionManifestDocumentRepair {
  fun inspectWholeDocument(text: String, sourceLabel: String): DecompositionManifestDocumentRepairDecision {
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
  ): DecompositionManifestDocumentRepairDecision? {
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
  ): DecompositionManifestDocumentRepairDecision {
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
  ): DecompositionManifestDocumentRepairDecision {
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
  ): DecompositionManifestDocumentRepairDecision =
    DecompositionManifestDocumentRepairDecision.Accepted(text, node, evidence)

  fun reject(
    code: FeatureTaskRuntimePhaseOutputFailureCode,
    reason: String,
    sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ): DecompositionManifestDocumentRepairDecision =
    DecompositionManifestDocumentRepairDecision.Rejected(code, reason, sourceLocation)
}

internal object DuplicateKeyMergeParser {
  private val jsonFactory = JsonFactory()
  private val yamlFactory = YAMLFactory()
  private val jsonMapper = ObjectMapper()

  fun merge(text: String, format: FeatureTaskRuntimePhaseOutputFormat): DuplicateKeyMerge? = try {
    val factory = if (format == FeatureTaskRuntimePhaseOutputFormat.JSON) jsonFactory else yamlFactory
    factory.createParser(text).use { parser ->
      if (parser.nextToken() == null) return@use null
      val tracker = MergeTracker(jsonMapper)
      val node = tracker.parseValue(parser)
      if (parser.nextToken() != null) return@use null
      if (!tracker.changed || !node.isObject) return@use null
      DuplicateKeyMerge(
        node = node,
        repairedText = jsonMapper.writeValueAsString(node),
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        firstDuplicateOffset = tracker.firstDuplicateOffset.coerceAtLeast(0),
      )
    }
  } catch (_: Exception) {
    null
  }

  private class MergeTracker(private val mapper: ObjectMapper) {
    var changed: Boolean = false
    var firstDuplicateOffset: Int = -1

    fun parseValue(parser: JsonParser): JsonNode {
      val factory = mapper.nodeFactory
      return when (val token = parser.currentToken()) {
        JsonToken.START_OBJECT -> parseObject(parser)
        JsonToken.START_ARRAY -> parseArray(parser)
        JsonToken.VALUE_STRING -> factory.textNode(parser.text)
        JsonToken.VALUE_NUMBER_INT,
        JsonToken.VALUE_NUMBER_FLOAT,
        -> factory.numberNode(parser.decimalValue)
        JsonToken.VALUE_TRUE -> factory.booleanNode(true)
        JsonToken.VALUE_FALSE -> factory.booleanNode(false)
        JsonToken.VALUE_NULL -> factory.nullNode()
        JsonToken.VALUE_EMBEDDED_OBJECT -> factory.pojoNode(parser.embeddedObject)
        else -> throw JsonParseException(parser, "Unsupported token $token")
      }
    }

    private fun parseObject(parser: JsonParser): ObjectNode {
      val obj = mapper.nodeFactory.objectNode()
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
          throw JsonParseException(parser, "Expected field name")
        }
        val name = parser.currentName()
        val fieldOffset = tokenOffset(parser)
        parser.nextToken()
        val value = parseValue(parser)
        val existing = obj.get(name)
        if (existing != null) {
          changed = true
          if (firstDuplicateOffset < 0) firstDuplicateOffset = fieldOffset
          obj.replace(name, mergeNodes(existing, value))
        } else {
          obj.replace(name, value)
        }
      }
      return obj
    }

    private fun parseArray(parser: JsonParser): ArrayNode {
      val arr = mapper.nodeFactory.arrayNode()
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        arr.add(parseValue(parser))
      }
      return arr
    }

    private fun mergeNodes(first: JsonNode, later: JsonNode): JsonNode {
      if (first == later) return first
      if (first is ObjectNode && later is ObjectNode) {
        val merged = first.deepCopy()
        val fields = later.fields()
        while (fields.hasNext()) {
          val field = fields.next()
          val existing = merged.get(field.key)
          if (existing == null) {
            merged.replace(field.key, field.value)
          } else {
            merged.replace(field.key, mergeNodes(existing, field.value))
          }
        }
        return merged
      }
      if (first is ArrayNode && later is ArrayNode) {
        val merged = first.deepCopy()
        later.forEach { element -> merged.add(element.deepCopy()) }
        return merged
      }
      return first
    }

    private fun tokenOffset(parser: JsonParser): Int {
      val location = parser.currentTokenLocation()
      val charOffset = location.charOffset
      if (charOffset >= 0L) return charOffset.toInt()
      val byteOffset = location.byteOffset
      return if (byteOffset >= 0L) byteOffset.toInt() else 0
    }
  }
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

internal data class DuplicateKeyMerge(
  val node: JsonNode,
  val repairedText: String,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val firstDuplicateOffset: Int,
)

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
