package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Filesystem writer for the single-spec `## Status`-block `Agent:` line (SKILL-89 Seam D, runtime
 * side). Sibling of [FileSystemFeatureTaskRuntimeRunInvariantsSource]; the only runtime seam that
 * reconciles the `## Status` block.
 *
 * The write is idempotent: when an `Agent:` line already exists under `## Status` it is replaced in
 * place (keeping the file byte-stable on a re-run with the same agent), otherwise a new line is
 * inserted immediately after the `Status:` line, matching its bullet/indent style. The `## Status`
 * heading is matched on a `^#{2,6}\s+Status\b` line and the section ends at the next heading, so the
 * line never escapes into `## Acceptance Criteria` (which the run-invariants reader keys off).
 */
@Inject
class FileSystemFeatureTaskRuntimeSpecStatusWriter : FeatureTaskRuntimeSpecStatusWriter {
  override fun writeFinalizingAgent(specPath: Path, finalizingAgentId: String) {
    val agentId = finalizingAgentId.trim()
    if (agentId.isEmpty()) {
      return
    }
    val normalizedPath = specPath.toAbsolutePath().normalize()
    if (!Files.isRegularFile(normalizedPath)) {
      return
    }
    val original = Files.readString(normalizedPath)
    val lines = original.split("\n").toMutableList()
    val statusHeadingIndex = lines.indexOfFirst { STATUS_HEADING.matches(it) }
    if (statusHeadingIndex < 0) {
      return
    }
    val sectionEnd = sectionEndExclusive(lines, statusHeadingIndex)
    val agentLineIndex = (statusHeadingIndex + 1 until sectionEnd).firstOrNull { AGENT_LINE.matches(lines[it]) }
    val statusLineIndex = (statusHeadingIndex + 1 until sectionEnd).firstOrNull { STATUS_LINE.matches(lines[it]) }
    val prefix = statusLineIndex?.let { bulletPrefix(lines[it]) } ?: "- "
    val agentLine = "${prefix}Agent: $agentId"
    when {
      agentLineIndex != null -> lines[agentLineIndex] = agentLine
      statusLineIndex != null -> lines.add(statusLineIndex + 1, agentLine)
      else -> lines.add(statusHeadingIndex + 1, agentLine)
    }
    val updated = lines.joinToString("\n")
    if (updated != original) {
      Files.writeString(normalizedPath, updated)
    }
  }

  // First heading line at or after the section body, or the end of file when none follows.
  private fun sectionEndExclusive(lines: List<String>, headingIndex: Int): Int {
    val next = (headingIndex + 1 until lines.size).firstOrNull { HEADING.matches(lines[it]) }
    return next ?: lines.size
  }

  // The leading bullet/indent of a `Status:` line (e.g. "- ", "  - ") so the inserted Agent line
  // matches its style; empty when the line carries no bullet.
  private fun bulletPrefix(statusLine: String): String = BULLET_PREFIX.find(statusLine)?.value ?: ""

  private companion object {
    val HEADING = Regex("""^#{2,6}\s+.+$""")
    val STATUS_HEADING = Regex("""^#{2,6}\s+Status\b.*$""")
    val STATUS_LINE = Regex("""^\s*(?:[-*]\s+)?Status\s*:.*$""")
    val AGENT_LINE = Regex("""^\s*(?:[-*]\s+)?Agent\s*:.*$""")
    val BULLET_PREFIX = Regex("""^\s*(?:[-*]\s+)?""")
  }
}
