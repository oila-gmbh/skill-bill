package skillbill.scaffold

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthoredContentValidatorTest {
  private val contentFile = Paths.get("skills/example/content.md")

  private fun authored(body: String): String = """
    ---
    name: example
    description: Example skill.
    ---

    # Example Skill

  """.trimIndent() + "\n" + body.trimStart('\n')

  @Test
  fun `valid authored content produces no issues`() {
    val issues = validateAuthoredContent(contentFile, authored("Authored guidance for the example skill.\n"))
    assertTrue(issues.isEmpty(), issues.joinToString("\n"))
  }

  @Test
  fun `each generated support pointer link is rejected`() {
    val pointers = listOf(
      "shell-ceremony.md",
      "review-scope.md",
      "stack-routing.md",
      "specialist-contract.md",
      "review-delegation.md",
      "shell-content-contract.md",
      "review-orchestrator.md",
      "telemetry-contract.md",
    )
    pointers.forEach { pointer ->
      val issues = validateAuthoredContent(
        contentFile,
        authored("Authored guidance.\n\nFollow [$pointer]($pointer) for the shared contract.\n"),
      )
      assertTrue(
        issues.any { it.contains("generated support pointer '$pointer'") },
        "expected pointer '$pointer' to be rejected, got: ${issues.joinToString("\n")}",
      )
    }
  }

  @Test
  fun `support pointer links inside fenced code blocks are allowed`() {
    val issues = validateAuthoredContent(
      contentFile,
      authored(
        """
        Authored guidance.

        ```
        Example of a generated link: [telemetry-contract.md](telemetry-contract.md)
        ```
        """.trimIndent() + "\n",
      ),
    )
    assertTrue(issues.isEmpty(), issues.joinToString("\n"))
  }

  @Test
  fun `pack-owned add-on links are not flagged`() {
    val issues = validateAuthoredContent(
      contentFile,
      authored("Authored guidance.\n\nScan [android-compose-review.md](android-compose-review.md).\n"),
    )
    assertTrue(issues.isEmpty(), issues.joinToString("\n"))
  }

  @Test
  fun `subagent spawn runtime notes heading is rejected`() {
    val issues = validateAuthoredContent(
      contentFile,
      authored("Authored guidance.\n\n## Subagent Spawn Runtime Notes\n\nProse.\n"),
    )
    assertTrue(
      issues.any { it.contains("auto-generated subagent runtime notes heading") },
      issues.joinToString("\n"),
    )
  }

  @Test
  fun `shortened subagent runtime notes heading is rejected at any level`() {
    listOf("## Subagent Runtime Notes", "### Subagent Runtime Notes").forEach { heading ->
      val issues = validateAuthoredContent(
        contentFile,
        authored("Authored guidance.\n\n$heading\n\nProse.\n"),
      )
      assertTrue(
        issues.any { it.contains("auto-generated subagent runtime notes heading") },
        "expected '$heading' to be rejected, got: ${issues.joinToString("\n")}",
      )
    }
  }

  @Test
  fun `ceremony pointer prose is rejected for each generated playbook`() {
    val cases = mapOf(
      "stack-routing" to "playbook",
      "review-delegation" to "playbook",
      "review-orchestrator" to "playbook",
      "review-scope" to "contract",
      "specialist-contract" to "contract",
      "shell-content-contract" to "contract",
      "shell-ceremony" to "ceremony",
      "telemetry-contract" to "contract",
    )
    cases.forEach { (slug, suffix) ->
      val issues = validateAuthoredContent(
        contentFile,
        authored("Authored guidance.\n\nFollow the shared $slug $suffix when delegating.\n"),
      )
      assertTrue(
        issues.any { it.contains("ceremony pointer 'shared $slug $suffix'") },
        "expected prose pointer 'shared $slug $suffix' to be rejected, got: ${issues.joinToString("\n")}",
      )
    }
  }

  @Test
  fun `ceremony pointer prose inside fenced code blocks is allowed`() {
    val issues = validateAuthoredContent(
      contentFile,
      authored(
        """
        Authored guidance.

        ```
        Example documenting the shared stack-routing playbook.
        ```
        """.trimIndent() + "\n",
      ),
    )
    assertTrue(issues.isEmpty(), issues.joinToString("\n"))
  }

  @Test
  fun `valid content does not trip new rules`() {
    val issues = validateAuthoredContent(
      contentFile,
      authored(
        """
        Authored guidance.

        ## Workflow

        1. Step one.
        2. Step two.

        ## Telemetry

        This skill emits a `skillbill_example_finished` event with `result` and `count`.
        """.trimIndent() + "\n",
      ),
    )
    assertEquals(emptyList(), issues, issues.joinToString("\n"))
  }

  @Test
  fun `each shipped governed content_md passes the new ceremony rules`() {
    val repoRoot = currentRepoRootForValidator()
    val sources = listOf(
      repoRoot.resolve("skills"),
      repoRoot.resolve("platform-packs"),
    )
    val contentFiles = sources
      .filter { java.nio.file.Files.isDirectory(it) }
      .flatMap { root ->
        java.nio.file.Files.walk(root).use { stream ->
          stream
            .filter { path -> path.fileName?.toString() == "content.md" }
            .toList()
        }
      }
    assertFalse(contentFiles.isEmpty(), "no content.md files were discovered under skills/ or platform-packs/")
    val violations = contentFiles.flatMap { contentFile ->
      validateAuthoredContent(contentFile, java.nio.file.Files.readString(contentFile))
        .filter { issue ->
          issue.contains("generated support pointer") ||
            issue.contains("auto-generated subagent runtime notes heading")
        }
    }
    assertTrue(violations.isEmpty(), "ceremony leaked back into authored content.md:\n${violations.joinToString("\n")}")
  }

  private fun currentRepoRootForValidator(): java.nio.file.Path {
    var current: java.nio.file.Path? = java.nio.file.Paths.get("").toAbsolutePath().normalize()
    while (current != null) {
      if (java.nio.file.Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts")) &&
        java.nio.file.Files.isDirectory(current.resolve("skills")) &&
        java.nio.file.Files.isDirectory(current.resolve("platform-packs"))
      ) {
        return current
      }
      current = current.parent
    }
    error("Could not locate skill-bill repo root for AuthoredContentValidatorTest")
  }
}
