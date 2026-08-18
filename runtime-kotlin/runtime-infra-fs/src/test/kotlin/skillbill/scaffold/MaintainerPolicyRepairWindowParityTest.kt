package skillbill.scaffold

import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MaintainerPolicyRepairWindowParityTest {
  @Test
  fun `AGENTS and CLAUDE describe repair window and post-repair verify gate`() {
    val repoRoot = repoRootFromTest()
    listOf("AGENTS.md", "CLAUDE.md").forEach { relativePath ->
      val content = Files.readString(repoRoot.resolve(relativePath))
      assertFalse(
        content.contains("no cache-bypassing confirmation pass"),
        "$relativePath must not deny the post-repair cache-bypassing verification gate",
      )
      assertContains(content, "repair window", ignoreCase = true)
      assertContains(content, "cache-bypassing verification gate")
      assertContains(content, "`detekt`")
      assertContains(content, "`ktlintCheck`")
    }
  }
}
