package skillbill.infrastructure.fs

import skillbill.model.RuntimeContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSystemReviewInputSourceTest {
  @Test
  fun `review input expands user home from runtime context`() {
    val userHome = Files.createTempDirectory("skill-bill-review-home").toAbsolutePath().normalize()
    val reviewFile = userHome.resolve("review.md")
    Files.writeString(reviewFile, "Review body")

    val (text, sourcePath) = FileSystemReviewInputSource(
      RuntimeContext(
        environment = emptyMap(),
        userHome = userHome,
      ),
    ).readInput("~/review.md")

    assertEquals("Review body", text)
    assertEquals(reviewFile.toString(), sourcePath)
  }
}
