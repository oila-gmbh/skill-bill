package skillbill.application.agentoutput

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

/**
 * Prefer stderr, else stdout, skipping known harness status banners so a non-zero exit is not blamed
 * on Codex's normal "Reading prompt from stdin..." line.
 */
internal fun agentFailureExcerpt(stderr: String, stdout: String, maxChars: Int): String? {
  val preferred = stderr.takeIf(String::isNotBlank) ?: stdout.takeIf(String::isNotBlank) ?: return null
  val signal = preferred.lineSequence()
    .map(String::trim)
    .filter { it.isNotBlank() }
    .filterNot(::isHarnessStatusBanner)
    .joinToString("\n")
    .ifBlank { preferred.trim() }
  return stderrExcerpt(signal, maxChars)
}

internal fun isHarnessStatusBanner(line: String): Boolean {
  val normalized = line.trim()
  return HARNESS_STATUS_BANNERS.any { banner -> normalized.equals(banner, ignoreCase = true) }
}

private val HARNESS_STATUS_BANNERS: List<String> = listOf(
  "Reading prompt from stdin...",
)
