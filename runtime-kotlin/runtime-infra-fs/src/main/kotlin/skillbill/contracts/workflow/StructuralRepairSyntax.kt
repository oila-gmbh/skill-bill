package skillbill.contracts.workflow

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.security.MessageDigest

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
