package skillbill.application.goalrunner

/**
 * Head+tail excerpt of child-process [stderr], bounded to [maxChars]. Head+tail rather than a plain
 * tail because a plain tail drops the exception type and message at the top of a crash stack trace.
 */
internal fun stderrExcerpt(stderr: String, maxChars: Int): String? {
  val trimmed = stderr.takeIf(String::isNotBlank) ?: return null
  if (trimmed.length <= maxChars) {
    return trimmed
  }
  val headChars = maxChars / 2
  val tailChars = maxChars - headChars
  val omitted = trimmed.length - headChars - tailChars
  return buildString {
    append(trimmed.take(headChars))
    append("\n…[")
    append(omitted)
    append(" chars omitted]…\n")
    append(trimmed.takeLast(tailChars))
  }
}
