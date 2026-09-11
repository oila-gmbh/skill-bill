package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliCodeReviewDriverRuntimeTest {
  @Test
  fun `code-review rejects removed agent2 option`() {
    val result = CliRuntime.run(
      listOf("code-review", "--agent2", "claude"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "agent2")
  }

  @Test
  fun `code-review rejects removed model2 option`() {
    val result = CliRuntime.run(
      listOf("code-review", "--model2", "gpt-4"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "model2")
  }
}
