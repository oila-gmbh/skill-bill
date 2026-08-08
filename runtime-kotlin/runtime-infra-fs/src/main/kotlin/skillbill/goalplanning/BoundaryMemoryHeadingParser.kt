package skillbill.goalplanning

import java.security.MessageDigest

/** One conforming boundary-memory entry: its governed H2 heading and the body span beneath it. */
data class BoundaryMemoryEntry(
  val headingId: String,
  val heading: String,
  val body: String,
)

/**
 * Splits a `bill-boundary-history` or `bill-boundary-decisions` file into its governed entries.
 *
 * Pure: no file IO, no network, no model call. The governed entry heading is `## [<date>] <title>`
 * for both files; every other region (H1 preamble, prose before the first conforming heading, other
 * heading levels) is skipped without inventing a heading and without dropping conforming entries
 * that follow it.
 */
object BoundaryMemoryHeadingParser {
  private val ENTRY_HEADING = Regex("^##\\s+\\[[^\\[\\]]+]\\s+\\S.*$")
  private const val HEADING_ID_DIGEST_CHARS = 12

  fun parse(sourcePath: String, content: String): List<BoundaryMemoryEntry> {
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val entries = mutableListOf<BoundaryMemoryEntry>()
    var heading: String? = null
    val body = StringBuilder()
    for (line in lines) {
      if (!ENTRY_HEADING.matches(line)) {
        if (heading != null) body.append(line).append('\n')
        continue
      }
      heading?.let { current -> entries.add(entry(sourcePath, entries.size, current, body.toString())) }
      heading = line.trim()
      body.setLength(0)
    }
    heading?.let { current -> entries.add(entry(sourcePath, entries.size, current, body.toString())) }
    return entries
  }

  /**
   * Stable across runs and across unrelated edits elsewhere in the file: source path plus ordinal
   * plus a digest of the heading text, so the resolver can re-verify a selection still names the
   * same heading instead of trusting the ordinal alone.
   */
  fun headingId(sourcePath: String, ordinal: Int, heading: String): String =
    "$sourcePath#$ordinal-${digest(heading)}"

  fun sourcePathOf(headingId: String): String? = headingId.substringBeforeLast('#', "").takeIf(String::isNotEmpty)

  private fun entry(sourcePath: String, ordinal: Int, heading: String, body: String) = BoundaryMemoryEntry(
    headingId = headingId(sourcePath, ordinal, heading),
    heading = heading,
    body = body.trim(),
  )

  private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(HEADING_ID_DIGEST_CHARS)
}
