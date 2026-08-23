package skillbill.goalplanning

import java.security.MessageDigest
import java.time.LocalDate

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
 * heading levels, fenced code blocks) is skipped without inventing a heading and without dropping
 * conforming entries that follow it.
 */
object BoundaryMemoryHeadingParser {
  private val ENTRY_HEADING = Regex("^##\\s+\\[[^\\[\\]]+]\\s+\\S.*$")
  private val ENTRY_DATE = Regex("^##\\s+\\[([^\\[\\]]+)]")
  private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})")
  private const val HEADING_ID_DIGEST_CHARS = 12
  private const val BYTE_ORDER_MARK = '\uFEFF'

  /**
   * A fence that never closes must not swallow the rest of the file: entries are written newest-first,
   * so one unterminated fence in the newest entry would hide every older entry from the catalog. That
   * case is reconciled by rescanning without fence tracking, which recovers the conforming headings —
   * at the cost of admitting any fenced example in that one file — rather than losing the history.
   */
  fun parse(sourcePath: String, content: String): List<BoundaryMemoryEntry> {
    val lines = content.trimStart(BYTE_ORDER_MARK).replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val fenced = scan(lines, honourFences = true)
    val parsed = if (fenced.unclosedFence) scan(lines, honourFences = false).entries else fenced.entries
    return withStableIds(sourcePath, parsed)
  }

  /**
   * Identity is the source path plus a digest of the heading text, never the entry's position. Both
   * boundary skills mandate newest-entry-first, so every append shifts every ordinal; a positional id
   * would invalidate the whole catalog on each write and resolve every later selection to nothing.
   * The `-<n>` suffix disambiguates entries whose heading text is byte-identical within one file, and
   * only those ids move when another copy of the same heading is prepended.
   */
  fun headingId(sourcePath: String, heading: String, occurrence: Int = 0): String =
    "$sourcePath#${digest(heading)}" + if (occurrence == 0) "" else "-$occurrence"

  fun sourcePathOf(headingId: String): String? = headingId.substringBeforeLast('#', "").takeIf(String::isNotEmpty)

  fun entryDate(heading: String): LocalDate? = runCatching {
    val dateText = ENTRY_DATE.find(heading.trim())?.groupValues?.get(1) ?: return null
    LocalDate.parse(dateText)
  }.getOrNull()

  private data class Scan(val entries: List<Pair<String, String>>, val unclosedFence: Boolean)

  private fun scan(lines: List<String>, honourFences: Boolean): Scan {
    val entries = mutableListOf<Pair<String, String>>()
    var heading: String? = null
    val body = StringBuilder()
    var openFence: String? = null
    for (line in lines) {
      if (honourFences) openFence = fenceStateAfter(openFence, line)
      if (openFence != null || !ENTRY_HEADING.matches(line)) {
        if (heading != null) body.append(line).append('\n')
      } else {
        heading?.let { current -> entries.add(current to body.toString()) }
        heading = line.trim()
        body.setLength(0)
      }
    }
    heading?.let { current -> entries.add(current to body.toString()) }
    return Scan(entries, unclosedFence = openFence != null)
  }

  private fun fenceStateAfter(openFence: String?, line: String): String? {
    val marker = FENCE.find(line)?.groupValues?.get(1) ?: return openFence
    return when {
      openFence == null -> marker
      marker[0] == openFence[0] && marker.length >= openFence.length -> null
      else -> openFence
    }
  }

  private fun withStableIds(sourcePath: String, parsed: List<Pair<String, String>>): List<BoundaryMemoryEntry> {
    val occurrences = mutableMapOf<String, Int>()
    return parsed.map { (heading, body) ->
      val occurrence = occurrences.merge(heading, 1, Int::plus)!! - 1
      BoundaryMemoryEntry(
        headingId = headingId(sourcePath, heading, occurrence),
        heading = heading,
        body = body.trim(),
      )
    }
  }

  private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(HEADING_ID_DIGEST_CHARS)
}
