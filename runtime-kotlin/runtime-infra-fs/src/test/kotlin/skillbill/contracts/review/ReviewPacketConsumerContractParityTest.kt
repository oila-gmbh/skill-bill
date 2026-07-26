package skillbill.contracts.review

import skillbill.review.context.model.ReviewPacketConsumerContract
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewPacketConsumerContractParityTest {
  @Test fun `launch specialist and consumer contracts are the same authoritative bytes`() {
    assertEquals(
      ReviewPacketConsumerContract.AUTHORITATIVE_LAUNCH_CONTRACT,
      ReviewPacketConsumerContract.CONSUMER_CONTRACT,
    )
  }

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

  @Test fun `governed prose and runtime use authoritative contract bytes`() {
    val markdown = Files.readString(contractPath())
    assertEquals(
      ReviewPacketConsumerContract.AUTHORITATIVE_LAUNCH_CONTRACT,
      authoritativeBlock(markdown, "authoritative-launch-contract"),
    )
    assertEquals(
      ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES,
      authoritativeBlock(markdown, "evidence-surface-rules"),
    )
    assertEquals(
      ReviewPacketConsumerContract.REPORT_STRUCTURE,
      authoritativeBlock(markdown, "report-structure"),
    )
  }

  @Test fun `delegation surfaces name the specialist contract without restating its marked rules`() {
    val root = contractPath().parent.parent.parent
    val delegation = Files.readString(root.resolve("orchestration/review-delegation/PLAYBOOK.md"))
    val sourcePath = ReviewPacketConsumerContract.SOURCE_PATH
    assertTrue(sourcePath in delegation)
    listOf("authoritative-launch-contract", "evidence-surface-rules", "report-structure").forEach { marker ->
      assertTrue("```$marker" !in delegation, "Delegation playbook must not restate authoritative '$marker' bytes.")
    }
  }

  private fun authoritativeBlock(markdown: String, name: String): String {
    val opening = "```$name\n"
    val body = markdown.substringAfter(opening, "")
    assertTrue(body.isNotEmpty(), "Missing authoritative '$name' block.")
    return body.substringBefore("\n```")
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
