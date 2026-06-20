package skillbill.application.goalrunner

/**
 * Produces a bounded head+tail excerpt of child-process [stderr] for diagnosis.
 *
 * A plain tail keeps only the bottom of a crash stack trace (`… main()`), discarding the exception
 * type and message at the top — the most diagnostic part. This splits the [maxChars] budget between
 * the head (exception + top frames) and the tail (most recent output), with an explicit marker for
 * the omitted middle. Returns null when there is no captured stderr.
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
