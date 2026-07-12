package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeModelAssignment
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.install.model.InstallAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeModelResolverTest {
  @Test
  fun `cli directive wins over matrix directive`() {
    val assignment = FeatureTaskRuntimeModelAssignment(
      perPhaseDirectives = mapOf("plan" to PhaseModelDirective("cli-model", "high")),
      matrix = matrix(),
    )

    assertEquals(
      PhaseModelDirective("cli-model", "high"),
      FeatureTaskRuntimeModelResolver.resolve("plan", "claude", assignment),
    )
  }

  @Test
  fun `matrix directive resolves against each phases actual agent`() {
    val assignment = FeatureTaskRuntimeModelAssignment(matrix = matrix())

    assertEquals(
      PhaseModelDirective("claude-opus", "high"),
      FeatureTaskRuntimeModelResolver.resolve("review", "claude", assignment),
    )
    assertEquals(
      PhaseModelDirective("gpt-sol", "xhigh"),
      FeatureTaskRuntimeModelResolver.resolve("review", "codex", assignment),
    )
  }

  @Test
  fun `no matrix or unknown agent resolves no directive`() {
    assertNull(
      FeatureTaskRuntimeModelResolver.resolve(
        "plan",
        "claude",
        FeatureTaskRuntimeModelAssignment(),
      ),
    )
    assertNull(
      FeatureTaskRuntimeModelResolver.resolve(
        "plan",
        "unknown",
        FeatureTaskRuntimeModelAssignment(matrix = matrix()),
      ),
    )
  }

  private fun matrix(): ExecutionMatrix = ExecutionMatrix(
    agents = mapOf(
      InstallAgent.CLAUDE to mapOf(ExecutionTier.REASONING to PhaseModelDirective("claude-opus", "high")),
      InstallAgent.CODEX to mapOf(ExecutionTier.REASONING to PhaseModelDirective("gpt-sol", "xhigh")),
    ),
  )
}
