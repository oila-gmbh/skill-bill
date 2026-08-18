package skillbill.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class BillCodeCheckRepairWindowContractTest {
  @Test
  fun `bill-code-check content is substantive and states repair window prohibitions`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-code-check/content.md"))
    assertTrue(content.lines().size > 10, "bill-code-check must not be frontmatter-only")
    assertContains(content, "## Repair Window")
    assertContains(content, "do not invoke")
    assertContains(content, "`detekt`")
    assertContains(content, "`ktlintCheck`")
    assertContains(content, "`test`")
    assertContains(content, "`compileKotlin`")
    assertContains(content, "subagent-delegated checks")
  }

  private fun repoRootFromTest(): Path {
    var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    while (!Files.isDirectory(current.resolve("skills"))) {
      val parent = current.parent ?: error("Could not locate repository root from ${Path.of(System.getProperty("user.dir"))}")
      current = parent
    }
    return current
  }
}
