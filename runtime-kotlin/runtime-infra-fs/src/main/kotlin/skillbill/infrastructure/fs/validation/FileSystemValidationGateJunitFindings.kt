package skillbill.infrastructure.fs.validation

import org.w3c.dom.Element

internal const val JUNIT_FAILURE_MESSAGE_MAX_CHARS: Int = 1_200

internal const val JUNIT_STACK_PREVIEW_MAX_FRAMES: Int = 8

private val JUNIT_STACK_FRAME =
  Regex("""^\s*at\s+(?:[\w.$]+/)?([\w.$]+)\(([^:)]+\.(?:kt|java)):(\d+)\)""")

internal fun junitFailureMessage(failure: Element): String {
  val attr = failure.getAttribute("message").trim()
  val body = failure.textContent?.trim().orEmpty()
  if (body.isBlank()) return attr
  if (attr.isBlank()) return truncateJunitFailureMessage(body)
  val preview = junitStackPreview(body)
  if (preview.isBlank()) return attr
  return truncateJunitFailureMessage("$attr\n$preview")
}

internal fun junitFailureLocation(testcase: Element, failureBody: String): String? {
  val fromAttrs = listOfNotNull(
    testcase.getAttribute("file").takeIf(String::isNotBlank),
    testcase.getAttribute("line").takeIf(String::isNotBlank),
  ).joinToString(":").ifBlank { null }
  if (fromAttrs != null) return fromAttrs
  return junitLocationFromStack(failureBody)
}

internal fun junitLocationFromStack(failureBody: String): String? {
  val frames = junitStackFrames(failureBody)
  val preferred = frames.firstOrNull { it.className.startsWith("skillbill.") } ?: frames.firstOrNull()
  return preferred?.let { "${it.file}:${it.line}" }
}

internal fun junitStackPreview(failureBody: String): String {
  val frames = junitStackFrames(failureBody)
  if (frames.isEmpty()) return ""
  val preferred = frames.filter { it.className.startsWith("skillbill.") }
  val chosen = (preferred.ifEmpty { frames }).take(JUNIT_STACK_PREVIEW_MAX_FRAMES)
  return chosen.joinToString("\n") { "at ${it.className}(${it.file}:${it.line})" }
}

private data class JunitStackFrame(
  val className: String,
  val file: String,
  val line: String,
)

private fun junitStackFrames(failureBody: String): List<JunitStackFrame> =
  failureBody.lineSequence().mapNotNull { line ->
    val match = JUNIT_STACK_FRAME.matchEntire(line) ?: return@mapNotNull null
    JunitStackFrame(
      className = match.groupValues[1],
      file = match.groupValues[2],
      line = match.groupValues[3],
    )
  }.toList()

private fun truncateJunitFailureMessage(message: String): String {
  if (message.length <= JUNIT_FAILURE_MESSAGE_MAX_CHARS) return message
  return message.take(JUNIT_FAILURE_MESSAGE_MAX_CHARS).trimEnd() + "…"
}
