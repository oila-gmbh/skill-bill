package skillbill.nativeagent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAgentValidationTest {
  @Test
  fun `provider-agnostic body passes validator`() {
    val repo = newRepoWithSource(body = "# Worker\n\nDo the work.")
    val report = validateRepoNativeAgents(repo)
    assertTrue(
      report.passed,
      "Expected report to pass, got issues:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `body with claude handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#claude}}claude only{{/claude}}")
  }

  @Test
  fun `body with codex handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#codex}}codex only{{/codex}}")
  }

  @Test
  fun `body with opencode handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#opencode}}opencode only{{/opencode}}")
  }

  @Test
  fun `body with junie handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#junie}}junie only{{/junie}}")
  }

  @Test
  fun `body with case-insensitive 'if provider ==' is rejected`() {
    assertProviderConditionalRejected("# Worker\n\nIF Provider == claude { ... }")
  }

  @Test
  fun `body with case-insensitive 'if (provider' is rejected`() {
    assertProviderConditionalRejected("# Worker\n\nif (Provider) { ... }")
  }

  private fun assertProviderConditionalRejected(body: String) {
    val repo = newRepoWithSource(body = body)
    val report = validateRepoNativeAgents(repo)
    assertFalse(report.passed, "Expected validator to reject body: $body")
    val expectedTail = "native agent bodies must be provider-agnostic; conditionals belong in the renderer"
    assertTrue(
      report.issues.any { it.endsWith(expectedTail) },
      "Expected an issue ending with '$expectedTail'; got:\n${report.issues.joinToString("\n")}",
    )
  }

  private fun newRepoWithSource(body: String): Path {
    val repo = Files.createTempDirectory("skillbill-validation-test")
    val skillDir = repo.resolve("skills/bill-validation-fixture/native-agents")
    Files.createDirectories(skillDir)
    val sourceFile = skillDir.resolve("bill-validation-fixture.md")
    val text = "---\n" +
      "name: bill-validation-fixture\n" +
      "description: Validation fixture.\n" +
      "---\n\n" +
      body + "\n"
    Files.writeString(sourceFile, text)
    return repo
  }

  @Test
  fun `passing fixture issues list is empty`() {
    val repo = newRepoWithSource(body = "# Worker\n\nDo the work.")
    val report = validateRepoNativeAgents(repo)
    assertEquals(emptyList(), report.issues)
  }
}
