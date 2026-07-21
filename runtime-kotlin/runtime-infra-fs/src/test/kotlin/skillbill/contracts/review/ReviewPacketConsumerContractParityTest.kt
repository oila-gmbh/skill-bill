package skillbill.contracts.review

import skillbill.review.context.model.ReviewPacketConsumerContract
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewPacketConsumerContractParityTest {
  @Test fun `governed prose and runtime list enumerate the same forbidden rediscovery items`() {
    val markdown = Files.readString(contractPath())
    val section = markdown
      .substringAfter(ReviewPacketConsumerContract.SECTION_HEADING, "")
      .substringBefore("\n## ")
    assertTrue(section.isNotBlank(), "Missing '${ReviewPacketConsumerContract.SECTION_HEADING}' section.")
    val documented = Regex("^- `([a-z_]+)`", RegexOption.MULTILINE)
      .findAll(section)
      .map { it.groupValues[1] }
      .toList()
    assertEquals(ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY, documented)
  }

  private fun contractPath(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(ReviewPacketConsumerContract.SOURCE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("Specialist contract not found under ${ReviewPacketConsumerContract.SOURCE_PATH}.")
  }
}
