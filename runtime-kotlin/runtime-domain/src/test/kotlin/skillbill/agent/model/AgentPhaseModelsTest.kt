package skillbill.agent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentPhaseModelsTest {
  @Test
  fun `phase models keep string handoff separate from typed launch plumbing`() {
    val input = AgentPhaseInput(
      input = "review prose",
      requestedAction = "verify claims",
    )
    val output = AgentPhaseOutput(output = "verified prose")

    assertEquals("review prose", input.input)
    assertEquals("verify claims", input.requestedAction)
    assertEquals("verified prose", output.output)
    assertEquals(String::class.java, AgentPhaseInput::class.java.getDeclaredField("input").type)
    assertEquals(String::class.java, AgentPhaseInput::class.java.getDeclaredField("requestedAction").type)
    assertEquals(String::class.java, AgentPhaseOutput::class.java.getDeclaredField("output").type)
    assertFalse(
      (AgentPhaseInput::class.java.declaredFields + AgentPhaseOutput::class.java.declaredFields)
        .any { it.name in setOf("repoRoot", "agentId", "evidenceBroker", "budget", "budgets") },
    )
  }
}
