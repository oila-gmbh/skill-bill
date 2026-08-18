package skillbill.launcher.agentrun

@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")
internal fun decodeCursorStreamJson(stdout: String): DecodedAgentRunOutput {
  if (stdout.isBlank()) {
    return DecodedAgentRunOutput("")
  }

  var terminalText: String? = null
  var longestAssistantText: String? = null
  var lastAssistantText: String? = null
  var assistantEventCount = 0
  var usage: com.fasterxml.jackson.databind.JsonNode? = null
  var decodedEnvelope = false
  var errorEvent = false
  var errorType: String? = null
  var errorMessage: String? = null
  var totalByteCount = 0
  val maxTotalBytes = 10_000_000 // 10MB limit for Cursor stream processing
  val cursorStreamPreviewLength = 100 // Characters to show in error messages
  val lines = stdout.lineSequence().toList()

  if (lines.isEmpty()) {
    return DecodedAgentRunOutput("")
  }

  lines.asSequence().takeWhile { line ->
    totalByteCount += line.toByteArray().size
    totalByteCount <= maxTotalBytes
  }.filter(String::isNotBlank).forEach { line ->
    val event =
      runCatching { structuredOutputMapper.readTree(line) }.getOrElse {
        throw skillbill.infrastructure.fs.CursorReviewStreamMalformedError(
          "Malformed Cursor stream JSONL line: ${line.take(cursorStreamPreviewLength)}",
          it,
        )
      }
    decodedEnvelope = true
    when (event.path("type").takeIf { it.isTextual }?.asText()) {
      "error" -> {
        errorEvent = true
        errorType = event.path("error_type").takeIf { it.isTextual }?.asText()
        errorMessage = event.path("message").takeIf { it.isTextual }?.asText()
        // Error is stored and thrown later after parsing completes
      }
      "assistant" -> {
        assistantEventCount += 1
        cursorAssistantText(event)?.let { text ->
          lastAssistantText = text
          if (text.length > (longestAssistantText?.length ?: 0)) longestAssistantText = text
        }
      }
      "result" -> {
        terminalText = event.path("result").takeIf { it.isTextual }?.asText()
        event.path("usage").takeUnless { it.isMissingNode || it.isNull }?.let { usage = it }
      }
    }
  }

  // Throw cursor-specific errors after parsing is complete (reduces throw count)
  if (errorEvent) {
    throw when (errorType) {
      "forbidden_operation" -> skillbill.infrastructure.fs.CursorReviewStreamForbiddenOperationError(
        errorMessage ?: "Cursor reported a forbidden operation",
      )
      "provider_failure" -> skillbill.infrastructure.fs.CursorReviewStreamProviderFailureError(
        errorMessage ?: "Cursor reported a provider failure",
      )
      "termination" -> skillbill.infrastructure.fs.CursorReviewStreamTerminationError(
        errorMessage ?: "Cursor process terminated prematurely",
      )
      else -> skillbill.infrastructure.fs.CursorReviewStreamError(
        errorMessage ?: "Cursor reported an unknown error",
      )
    }
  }

  val harvested = pickCursorHarvest(terminalText, lastAssistantText, longestAssistantText)
  return DecodedAgentRunOutput(
    text = harvested,
    inputTokens = usage.cursorTokens("inputTokens", "input_tokens"),
    cachedInputTokens = usage.cursorTokens("cachedInputTokens", "cached_input_tokens"),
    outputTokens = usage.cursorTokens("outputTokens", "output_tokens"),
    reasoningTokens = usage.cursorTokens("reasoningTokens", "reasoning_tokens"),
    totalTokens = usage.cursorTokens("totalTokens", "total_tokens"),
    assistantEventCount = assistantEventCount.takeIf { decodedEnvelope },
    rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS).takeIf { harvested.isBlank() },
  )
}

/** Cursor emits camelCase usage keys; older captures and fixtures use snake_case. Accept both. */
private fun com.fasterxml.jackson.databind.JsonNode?.cursorTokens(vararg fields: String): Long? =
  this?.let { node -> fields.firstNotNullOfOrNull { field -> node.longOrNull(field) } }

private fun pickCursorHarvest(
  terminalText: String?,
  lastAssistantText: String?,
  longestAssistantText: String?,
): String {
  val last = lastAssistantText?.trim().orEmpty()
  if (isStrictReviewRegister(last)) return last
  val source = terminalText?.takeIf { it.isNotBlank() } ?: longestAssistantText.orEmpty()
  return harvestCursorRegister(source)
}

private fun harvestCursorRegister(text: String): String {
  val trimmed = text.trim()
  if (trimmed.isEmpty() || isStrictReviewRegister(trimmed)) return trimmed
  peelTrailingNoFindings(trimmed)?.let { return it }
  val split = insertCursorRegisterBoundaries(trimmed)
  val findingLines = split.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && FINDING_LINE_START.containsMatchIn(it) }
    .toList()
  if (findingLines.isNotEmpty()) return findingLines.joinToString("\n")
  if (split.lineSequence().any { it.trim() == NO_FINDINGS_TOKEN }) return NO_FINDINGS_TOKEN
  return trimmed
}

private fun insertCursorRegisterBoundaries(text: String): String {
  val withFindings = GLUED_FINDING_START.replace(text, "\n$1")
  return GLUED_TRAILING_NO_FINDINGS.replace(withFindings, "\n$1")
}

private fun isStrictReviewRegister(text: String): Boolean {
  val trimmed = text.trim()
  if (trimmed == NO_FINDINGS_TOKEN) return true
  val lines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  return lines.isNotEmpty() && lines.all { FINDING_LINE_START.containsMatchIn(it) }
}

private fun peelTrailingNoFindings(text: String): String? {
  val trimmed = text.trim()
  if (trimmed == NO_FINDINGS_TOKEN) return NO_FINDINGS_TOKEN
  if (FINDING_CANDIDATE.containsMatchIn(trimmed)) return null
  if (TRAILING_NO_FINDINGS.containsMatchIn(trimmed)) return NO_FINDINGS_TOKEN
  return null
}

private fun cursorAssistantText(event: com.fasterxml.jackson.databind.JsonNode): String? {
  val content = event.path("message").path("content")
  if (content.isArray) {
    val joined = content.mapNotNull { part -> part.path("text").takeIf { it.isTextual }?.asText() }
      .joinToString("")
    return joined.takeIf(String::isNotBlank)
  }
  return event.path("message").path("text").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
    ?: event.path("text").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
}

private const val NO_FINDINGS_TOKEN = "NO_FINDINGS"
private val FINDING_LINE_START = Regex("^\\s*(?:-\\s+)?\\[F-\\d{3}]")
private val FINDING_CANDIDATE = Regex("\\[F-\\d+]")
private val TRAILING_NO_FINDINGS = Regex("(?:^|[^A-Z0-9_])NO_FINDINGS\\s*$")
private val GLUED_FINDING_START = Regex("(?<![\\n\\r])(\\[F-\\d{3}])")
private val GLUED_TRAILING_NO_FINDINGS = Regex("(?<![\\n\\r])(NO_FINDINGS)\\s*$")
