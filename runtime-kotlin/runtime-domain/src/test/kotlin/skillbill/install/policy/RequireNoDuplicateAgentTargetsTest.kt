package skillbill.install.policy

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class RequireNoDuplicateAgentTargetsTest {
  private fun claudeAt(path: String) = InstallAgentTarget(
    agent = InstallAgent.CLAUDE,
    path = Path.of(path),
    source = InstallAgentTargetSource.MANUAL,
  )

  @Test
  fun `accepts multiple claude rows at distinct roots`() {
    requireNoDuplicateAgentTargets(
      "targets",
      listOf(
        claudeAt("/home/u/.claude/commands"),
        claudeAt("/home/u/.claude-work/commands"),
      ),
    )
  }

  @Test
  fun `rejects a true same agent same path duplicate`() {
    val error = assertFailsWith<IllegalArgumentException> {
      requireNoDuplicateAgentTargets(
        "targets",
        listOf(
          claudeAt("/home/u/.claude/commands"),
          claudeAt("/home/u/.claude/commands"),
        ),
      )
    }
    assertContains(error.message.orEmpty(), "claude at")
  }
}
