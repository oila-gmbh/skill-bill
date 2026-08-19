package skillbill.application.review

import skillbill.review.context.model.ReviewIntegrationTerminalOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewFailureMatrixTest {
  @Test fun `historical matrix has one disposition and evidence reference for every item`() {
    val matrix = findMatrix()
    val rowPattern = Regex(
      "^\\|\\s*(\\d+)\\s*\\|\\s*[^|]+\\|\\s*(resolved|remaining|not applicable|regression)\\s*\\|" +
        "\\s*[^|]+\\|\\s*([^|]+)\\|.*",
    )
    val rows = Files.readAllLines(matrix).mapNotNull { rowPattern.matchEntire(it) }
    assertEquals((1..47).toList(), rows.map { it.groupValues[1].toInt() })
    assertTrue(rows.all { it.groupValues[3].trim().isNotEmpty() })
  }

  /**
   * The integration pass has its own terminal-state vocabulary, and it must stay identical to the
   * governed schema enum: a state the runtime can emit but the schema will not accept is a review
   * that dies at the validator instead of reporting how it actually ended.
   */
  @Test fun `integration terminal states match the governed schema enum`() {
    val schema = Files.readString(findRepositoryFile("orchestration/contracts/review-context-schema.yaml"))
    val enumLine = schema.lines()
      .dropWhile { !it.contains("integration_accounting:") }
      .first { it.trimStart().startsWith("enum: [") }
    val governed = enumLine.substringAfter("[").substringBefore("]").split(",").map { it.trim() }.toSet()

    assertEquals(governed, ReviewIntegrationTerminalOutcome.entries.map { it.wireValue }.toSet())
  }

  @Test fun `only a settled integration pass is a durable boundary`() {
    val durable = ReviewIntegrationTerminalOutcome.entries.filter { it.isDurablyComplete }.toSet()

    assertEquals(
      setOf(
        ReviewIntegrationTerminalOutcome.COMPLETED,
        ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE,
        ReviewIntegrationTerminalOutcome.NO_OP_RESUME,
      ),
      durable,
      "A crashed, timed-out, or interrupted integration pass must be re-run by the next resume.",
    )
  }

  private fun findRepositoryFile(relative: String): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(relative)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("Repository file '$relative' not found.")
  }

  private fun findMatrix(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve("docs/delegated-review/failure-matrix.md")
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("SKILL-145 failure matrix not found.")
  }
}
