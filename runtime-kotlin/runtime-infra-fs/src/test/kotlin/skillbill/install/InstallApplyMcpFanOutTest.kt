package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.runtime.InstallOperations
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallApplyMcpFanOutTest : InstallApplyTestSupport() {
  @Test
  fun `apply fans MCP registration across multiple claude profiles`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    val work = fixture.home.resolve(".claude-work")
    Files.createDirectories(work)
    Files.createFile(work.resolve(".claude.json"))
    val plan = InstallOperations.planInstall(
      fixture.request(agents = setOf(InstallAgent.CLAUDE)),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val claudeOutcome = result.mcpRegistrationOutcomes.single { outcome -> outcome.agent == InstallAgent.CLAUDE }
    assertEquals(McpRegistrationApplyStatus.SUCCESS, claudeOutcome.status)
    assertTrue(claudeOutcome.profiles.size > 1, "expected fan-out across profiles: ${claudeOutcome.profiles}")
    assertEquals(
      setOf(fixture.home.resolve(".claude.json"), work.resolve(".claude.json")),
      claudeOutcome.profiles.map { profile -> profile.configPath }.toSet(),
    )
    assertContains(claudeOutcome.message, "Profiles (${claudeOutcome.profiles.size}):")
    assertContains(claudeOutcome.message, fixture.home.resolve(".claude.json").toString())
    assertContains(claudeOutcome.message, work.resolve(".claude.json").toString())
  }

  @Test
  fun `apply reports claude profiles already written when one profile fails`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    val work = fixture.home.resolve(".claude-work")
    Files.createDirectories(work)
    val malformed = work.resolve(".claude.json")
    Files.writeString(malformed, "{ not valid json")
    val defaultConfig = fixture.home.resolve(".claude.json")
    val plan = InstallOperations.planInstall(
      fixture.request(agents = setOf(InstallAgent.CLAUDE)),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    val claudeOutcome = result.mcpRegistrationOutcomes.single { outcome -> outcome.agent == InstallAgent.CLAUDE }
    assertEquals(McpRegistrationApplyStatus.FAILED, claudeOutcome.status)
    assertEquals(listOf(defaultConfig), claudeOutcome.profiles.map { profile -> profile.configPath })
    assertContains(claudeOutcome.message, malformed.toString())
    assertContains(claudeOutcome.message, "Already updated: $defaultConfig")
    assertTrue(
      result.warnings.any { warning ->
        warning.kind == InstallApplyIssueKind.MCP_REGISTRATION_FAILED &&
          warning.agent == InstallAgent.CLAUDE
      },
    )
    assertEquals("{ not valid json", Files.readString(malformed))
  }
}
