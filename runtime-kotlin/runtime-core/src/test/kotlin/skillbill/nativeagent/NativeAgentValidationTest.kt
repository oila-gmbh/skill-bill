package skillbill.nativeagent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
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

  @Test
  fun `governed content composition resolves through platform manifest declared files`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)
    val sourcePath = NativeAgentOperations.discoverRepoNativeAgentSources(repo).single()
    val source = parseNativeAgentSource(sourcePath)

    val target = resolveNativeAgentCompositionTarget(repo, source)
    val expectedTarget = repo.resolve(
      "platform-packs/fixture/code-review/bill-fixture-code-review-architecture/content.md",
    )

    assertEquals(expectedTarget, target?.contentPath)
    assertEquals(NativeAgentCompositionTargetSource.PlatformManifest, target?.source)
    assertEquals(emptyList(), validateRepoNativeAgents(repo).issues)
  }

  @Test
  fun `missing governed content composition target is reported as validation issue`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = false)

    val report = validateRepoNativeAgents(repo)

    assertFalse(report.passed)
    val issue = report.issues.single()
    assertContains(issue, "bill-fixture-code-review-architecture")
    assertContains(issue, "content.md")
  }

  @Test
  fun `platform pack composition does not fall back to undeclared sibling content`() {
    val repo = newRepoWithComposedPlatformAgent(
      writeAreaContent = true,
      declaredSourceName = "bill-fixture-unregistered",
    )

    val report = validateRepoNativeAgents(repo)

    assertFalse(report.passed)
    val issue = report.issues.single()
    assertContains(issue, "could not resolve a corresponding content.md")
    assertContains(issue, "bill-fixture-unregistered")
  }

  @Test
  fun `malformed composition directive is reported as validation issue`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true, composeDirective = "local-file")

    val report = validateRepoNativeAgents(repo)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "unsupported native agent compose directive 'local-file'" in issue },
      "Expected malformed compose directive issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `native agent source discovery still returns composed source in place`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)

    val sources = NativeAgentOperations.discoverRepoNativeAgentSources(repo)

    assertEquals(
      listOf(
        repo.resolve(
          "platform-packs/fixture/code-review/bill-fixture-code-review/native-agents/" +
            "bill-fixture-code-review-architecture.md",
        ),
      ),
      sources,
    )
  }

  private fun newRepoWithComposedPlatformAgent(
    writeAreaContent: Boolean,
    composeDirective: String = "governed-content",
    declaredSourceName: String = "bill-fixture-code-review-architecture",
  ): Path {
    val repo = Files.createTempDirectory("skillbill-composed-agent-test")
    val packRoot = repo.resolve("platform-packs/fixture")
    Files.createDirectories(packRoot)
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: fixture
      contract_version: "1.1"
      routing_signals:
        strong:
          - ".fixture"
        tie_breakers: []
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/bill-fixture-code-review/content.md
        areas:
          architecture: code-review/bill-fixture-code-review-architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture review"
      """.trimIndent() + "\n",
    )
    writeContent(
      packRoot.resolve("code-review/bill-fixture-code-review/content.md"),
      "bill-fixture-code-review",
    )
    if (writeAreaContent) {
      writeContent(
        packRoot.resolve("code-review/bill-fixture-code-review-architecture/content.md"),
        "bill-fixture-code-review-architecture",
      )
    }
    val nativeAgentDir = packRoot.resolve("code-review/bill-fixture-code-review/native-agents")
    Files.createDirectories(nativeAgentDir)
    val sourceName = declaredSourceName
    Files.writeString(
      nativeAgentDir.resolve("$sourceName.md"),
      "---\n" +
        "name: $sourceName\n" +
        "description: Architecture worker.\n" +
        "compose: $composeDirective\n" +
        "---\n\n",
    )
    if (sourceName != "bill-fixture-code-review-architecture") {
      writeContent(
        packRoot.resolve("code-review/$sourceName/content.md"),
        sourceName,
      )
    }
    return repo
  }

  private fun writeContent(path: Path, name: String) {
    Files.createDirectories(path.parent)
    Files.writeString(
      path,
      """
      ---
      name: $name
      description: Fixture content.
      ---

      # Fixture

      Use this governed content.
      """.trimIndent() + "\n",
    )
  }
}
