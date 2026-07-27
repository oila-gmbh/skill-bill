package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.runtime.InstallOperations
import skillbill.launcher.mcp.McpRegistrationOperations
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursorAgentPathAndDetectionTest {
  @Test
  fun `cursor skill target resolves under the cursor home and never the shared agents root`() {
    val home = Files.createTempDirectory("skillbill-cursor-path")

    val path = InstallOperations.agentPath("cursor", home, environment = emptyMap())

    assertEquals(home.resolve(".cursor/skills"), path)
    assertFalse(path.startsWith(home.resolve(".agents")))
  }

  @Test
  fun `cursor detection is negative without a cursor home and creates nothing`() {
    val home = Files.createTempDirectory("skillbill-cursor-absent")

    val targets = InstallOperations.detectAgentTargets(home, environment = emptyMap())

    assertFalse(targets.any { target -> target.name == "cursor" })
    assertFalse(Files.exists(home.resolve(".cursor")))
  }

  @Test
  fun `cursor detection is positive for an existing cursor home`() {
    val home = Files.createTempDirectory("skillbill-cursor-present")
    Files.createDirectories(home.resolve(".cursor"))

    val targets = InstallOperations.detectAgentTargets(home, environment = emptyMap())
    val cursor = targets.single { target -> target.name == "cursor" }

    assertEquals(home.resolve(".cursor/skills"), cursor.path)
    assertFalse(Files.exists(home.resolve(".cursor/skills")))
  }

  @Test
  fun `cursor detection is positive when only the skills directory exists`() {
    val home = Files.createTempDirectory("skillbill-cursor-skills-only")
    Files.createDirectories(home.resolve(".cursor/skills"))

    val targets = InstallOperations.detectAgentTargets(home, environment = emptyMap())

    assertTrue(targets.any { target -> target.name == "cursor" })
  }

  @Test
  fun `unknown agent ids still fail loudly with cursor listed as supported`() {
    val home = Files.createTempDirectory("skillbill-cursor-unknown")

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.agentPath("not-an-agent", home, environment = emptyMap())
    }

    assertContains(error.message.orEmpty(), "Unknown agent 'not-an-agent'")
    assertContains(error.message.orEmpty(), "cursor")
  }

  @Test
  fun `cursor mcp config path resolves to the cursor home`() {
    val home = Files.createTempDirectory("skillbill-cursor-mcp")

    assertEquals(
      home.resolve(".cursor/mcp.json"),
      McpRegistrationOperations.configPathFor(InstallAgent.CURSOR, home),
    )
  }
}
