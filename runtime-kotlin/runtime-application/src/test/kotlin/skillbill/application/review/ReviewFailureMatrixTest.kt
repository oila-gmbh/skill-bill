package skillbill.application.review

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
