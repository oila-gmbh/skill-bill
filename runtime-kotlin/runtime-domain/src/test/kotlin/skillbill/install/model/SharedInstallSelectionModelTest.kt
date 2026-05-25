package skillbill.install.model

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInstallSelectionModelTest {
  @Test
  fun `shared install selection contains only runtime install choices`() {
    assertEquals(
      setOf("selectedAgents", "platformPackSelection", "telemetryLevel", "mcpRegistrationChoice"),
      SharedInstallSelection::class.java.declaredFields
        .filterNot { field -> field.isSynthetic }
        .mapTo(mutableSetOf()) { field -> field.name },
    )
  }

  @Test
  fun `resolved installed agents derive only reusable links from non failure apply results`() {
    val successResult = installApplyResult(
      status = InstallApplyStatus.SUCCESS,
      links = listOf(
        link(InstallAgent.CODEX, InstallAgentLinkStatus.CREATED),
        link(InstallAgent.CLAUDE, InstallAgentLinkStatus.SKIPPED),
        link(InstallAgent.OPENCODE, InstallAgentLinkStatus.WARNING),
        link(InstallAgent.JUNIE, InstallAgentLinkStatus.FAILED),
      ),
    )
    val warningResult = installApplyResult(
      status = InstallApplyStatus.WARNING,
      links = listOf(
        link(InstallAgent.CODEX, InstallAgentLinkStatus.CREATED),
        link(InstallAgent.CLAUDE, InstallAgentLinkStatus.SKIPPED),
        link(InstallAgent.JUNIE, InstallAgentLinkStatus.FAILED),
      ),
    )
    val failureResult = installApplyResult(
      status = InstallApplyStatus.FAILURE,
      links = listOf(
        link(InstallAgent.CODEX, InstallAgentLinkStatus.CREATED),
        link(InstallAgent.CLAUDE, InstallAgentLinkStatus.SKIPPED),
      ),
    )

    assertEquals(setOf(InstallAgent.CODEX, InstallAgent.CLAUDE), successResult.resolvedInstalledAgents.agents)
    assertEquals(setOf(InstallAgent.CODEX, InstallAgent.CLAUDE), warningResult.resolvedInstalledAgents.agents)
    assertEquals(emptySet(), failureResult.resolvedInstalledAgents.agents)
  }

  private fun installApplyResult(
    status: InstallApplyStatus,
    links: List<InstallAgentSkillLinkOutcome>,
  ): InstallApplyResult = InstallApplyResult(
    status = status,
    skills = listOf(
      InstallAppliedSkill(
        skillName = "bill-code-review",
        kind = InstallPlanSkillKind.BASE,
        sourceDir = Path.of("/repo/skills/bill-code-review"),
        staging = InstallSkillStagingOutcome(
          status = InstallSkillStagingStatus.STAGED,
          sourceDir = Path.of("/repo/skills/bill-code-review"),
          stagingDir = Path.of("/home/.skill-bill/installed-skills/bill-code-review-hash"),
        ),
        links = links,
      ),
    ),
    nativeAgents = emptyList(),
    telemetryOutcome = InstallTelemetryApplyOutcome(
      level = InstallTelemetryLevel.ANONYMOUS,
      status = InstallTelemetryApplyStatus.SUCCESS,
    ),
    mcpRegistrationOutcomes = listOf(
      McpRegistrationApplyOutcome(
        agent = InstallAgent.OPENCODE,
        status = McpRegistrationApplyStatus.SUCCESS,
      ),
    ),
    warnings = emptyList(),
    failures = emptyList(),
    windowsSymlinkOutcome = WindowsSymlinkApplyOutcome(
      preflight = WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      ),
      fallbackState = WindowsSymlinkFallbackState.NOT_REQUIRED,
    ),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationIntent = McpRegistrationIntent(
      register = true,
      runtimeMcpBin = Path.of("/runtime-mcp"),
      agents = listOf(InstallAgent.OPENCODE),
    ),
  )

  private fun link(agent: InstallAgent, status: InstallAgentLinkStatus): InstallAgentSkillLinkOutcome =
    InstallAgentSkillLinkOutcome(
      agent = agent,
      targetDir = Path.of("/home/.${agent.id}/skills"),
      linkPath = Path.of("/home/.${agent.id}/skills/bill-code-review"),
      linkTarget = Path.of("/home/.skill-bill/installed-skills/bill-code-review-hash"),
      status = status,
    )
}
