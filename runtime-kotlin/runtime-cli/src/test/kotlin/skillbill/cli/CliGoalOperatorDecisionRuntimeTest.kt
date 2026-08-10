package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.ports.agentrun.ExecutableLookup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-178 subtask 3: a documented skill-bill goal command records operator decisions for paused
 * goal subtasks without hand-editing durable state or decomposition-manifest.yaml.
 */
class CliGoalOperatorDecisionRuntimeTest {
  @Test
  fun `goal operator-decision help documents the paused-subtask decision surface`() {
    val result = CliRuntime.run(
      listOf("goal", "operator-decision", "--help"),
      CliRuntimeContext(environment = emptyMap(), executableLookup = ExecutableLookup { true }),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val help = result.stdout.replace(Regex("\\s+"), " ")
    assertContains(help, "retry_fix")
    assertContains(help, "accept_and_advance")
    assertContains(help, "--subtask")
    assertContains(help, "--decision")
    assertTrue(
      help.contains("decomposition-manifest") || help.contains("durable state"),
      "help must say the command avoids hand-editing durable state or the manifest: $help",
    )
  }
}
