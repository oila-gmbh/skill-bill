package skillbill.infrastructure.fs.validation

internal object FileSystemValidationGateGradleSpotlessExcerpts {
  private const val DEFAULT_MAX_CHARS = 480
  private const val BLANK_BREAK_MIN = 80
  private val STEP_PROBLEM =
    Regex("""^Step '([^']+)' found problem in '([^']+)':\s*$""")

  fun excerpt(stdout: String, maxChars: Int = DEFAULT_MAX_CHARS): String? {
    val lines = stdout.lineSequence().map { it.trimEnd() }.toList()
    val start = lines.indexOfFirst { isStart(it.trim()) }
    if (start < 0) return null
    return buildExcerpt(lines, start, maxChars)
  }

  private fun isStart(line: String): Boolean = STEP_PROBLEM.containsMatchIn(line) ||
    line.contains("format violations", ignoreCase = true) ||
    line.contains("Violations detected", ignoreCase = true)

  private fun buildExcerpt(lines: List<String>, start: Int, maxChars: Int): String? {
    val excerpt = buildString {
      var lineIndex = start
      var stop = false
      while (lineIndex < lines.size && !stop && length < maxChars) {
        val line = lines[lineIndex].trim()
        if (shouldStop(line, lineIndex, start, length)) {
          stop = true
        } else {
          if (length > 0) append(' ')
          append(line)
          lineIndex++
        }
      }
    }.trim()
    return excerpt.takeIf { it.isNotBlank() }
  }

  private fun shouldStop(line: String, lineIndex: Int, start: Int, length: Int): Boolean =
    (line.isEmpty() && lineIndex > start && length > BLANK_BREAK_MIN) ||
      (line.startsWith("at ") && line.contains("diffplug")) ||
      (line.startsWith("> Task :") && lineIndex > start)
}
