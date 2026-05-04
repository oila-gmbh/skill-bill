package skillbill.cli

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliAuthoringParityTest {
  @Test
  fun `native authoring inspection commands stay available through the kotlin cli`() {
    val repoRoot = outerRepoRoot()
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring")
    val context = CliRuntimeContext(userHome = tempDir)

    val listed =
      runJson(
        listOf(
          "list",
          "--repo-root",
          repoRoot.toString(),
          "--skill-name",
          "bill-feature-implement",
          "--format",
          "json",
        ),
        context,
      )
    val shown =
      runJson(
        listOf(
          "show",
          "bill-feature-implement",
          "--repo-root",
          repoRoot.toString(),
          "--content",
          "none",
          "--format",
          "json",
        ),
        context,
      )
    val explained =
      runJson(
        listOf(
          "explain",
          "bill-feature-implement",
          "--repo-root",
          repoRoot.toString(),
          "--format",
          "json",
        ),
        context,
      )

    assertEquals(1, listed["skill_count"])
    assertEquals("bill-feature-implement", shown["skill_name"])
    assertEquals("bill-feature-implement", (explained["skill"] as Map<*, *>)["skill_name"])
  }

  @Test
  fun `native authoring validation command stays available through the kotlin cli`() {
    val repoRoot = outerRepoRoot()
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-validation")
    val context = CliRuntimeContext(userHome = tempDir)
    val validated =
      runJson(
        listOf(
          "validate",
          "--repo-root",
          repoRoot.toString(),
          "--skill-name",
          "bill-feature-implement",
          "--format",
          "json",
        ),
        context,
      )

    assertEquals("pass", validated["status"])
  }

  @Test
  fun `native authoring validation reports render drift and skill shape issues`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-validate-drift")
    val repoRoot = authoringFixtureRepo(tempDir.resolve("drift-repo"), "bill-drift-fixture")
    val skillFile = repoRoot.resolve("skills/bill-drift-fixture/SKILL.md")
    Files.writeString(skillFile, Files.readString(skillFile) + "\n### Invalid nested heading\n")
    val context = CliRuntimeContext(userHome = tempDir)

    val result =
      CliRuntime.run(
        listOf(
          "validate",
          "--repo-root",
          repoRoot.toString(),
          "--skill-name",
          "bill-drift-fixture",
          "--format",
          "json",
        ),
        context,
      )
    val payload = decodeJsonObject(result.stdout)
    val issues = payload["issues"] as List<*>

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("fail", payload["status"])
    assertEquals(true, issues.any { issue -> issue.toString().contains("render drift") })
    assertEquals(true, issues.any { issue -> issue.toString().contains("H3+ heading") })
  }

  @Test
  fun `native repo wide authoring validation reports render drift`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-validate-repo-drift")
    val repoRoot = authoringFixtureRepo(tempDir.resolve("repo-drift"), "bill-repo-drift-fixture")
    val context = CliRuntimeContext(userHome = tempDir)

    val result =
      CliRuntime.run(
        listOf(
          "validate",
          "--repo-root",
          repoRoot.toString(),
          "--format",
          "json",
        ),
        context,
      )
    val payload = decodeJsonObject(result.stdout)
    val issues = payload["issues"] as List<*>

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("fail", payload["status"])
    assertEquals("repo", payload["mode"])
    assertEquals(true, issues.any { issue -> issue.toString().contains("render drift") })
  }

  @Test
  fun `native wrapper regeneration and content mutation commands stay available through the kotlin cli`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-mutation")
    val context = CliRuntimeContext(userHome = tempDir)

    assertWrapperCommandRegenerates("upgrade", tempDir, context)
    assertWrapperCommandRegenerates("render", tempDir, context)
    assertEditBodyFileUpdatesContent(tempDir, context)
    assertFillBodyUpdatesContent(tempDir, context)
  }

  @Test
  fun `interactive authoring modes are retired with stable replacements`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-retired-authoring")
    val context = CliRuntimeContext(userHome = tempDir)

    listOf(
      listOf("new-skill", "--interactive", "--format", "json") to "skill-bill new-skill --payload <file>",
      listOf("new", "--interactive", "--format", "json") to "skill-bill new --payload <file>",
      listOf("new-addon", "--interactive", "--format", "json") to
        "skill-bill new-addon --platform <platform> --name <name> --body-file <file>",
      listOf("create-and-fill", "--interactive", "--format", "json") to
        "skill-bill create-and-fill --payload <file> --body-file <file>",
      listOf("edit", "bill-feature-implement", "--repo-root", outerRepoRoot().toString(), "--format", "json") to
        "skill-bill fill bill-feature-implement --body-file <file>",
      listOf(
        "edit",
        "bill-feature-implement",
        "--repo-root",
        outerRepoRoot().toString(),
        "--editor",
        "--format",
        "json",
      ) to "skill-bill fill bill-feature-implement --body-file <file>",
    ).forEach { (arguments, replacement) ->
      val result = CliRuntime.run(arguments, context)
      val payload = decodeJsonObject(result.stdout)

      assertEquals(1, result.exitCode, result.stdout)
      assertEquals("unsupported", payload["status"])
      assertEquals(true, payload["error"].toString().contains("retired in SKILL-32"))
      assertEquals(true, payload["error"].toString().contains(replacement))
    }
  }
}

private fun runJson(arguments: List<String>, context: CliRuntimeContext): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}

private fun outerRepoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasSkills = Files.isDirectory(current.resolve("skills"))
    if (hasSettings && hasSkills) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repository root from ${Path.of("").toAbsolutePath().normalize()}")
}

private fun authoringFixtureRepo(repoRoot: Path, skillName: String): Path {
  val skillDir = repoRoot.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("SKILL.md"),
    """
    ---
    name: $skillName
    description: Fixture skill for CLI authoring tests.
    ---

    ## Descriptor

    Stale descriptor that should be regenerated.

    ## Execution

    Stale execution section.

    ## Ceremony

    Stale ceremony section.
    """.trimIndent() + "\n",
  )
  Files.writeString(
    skillDir.resolve("content.md"),
    """
    # Fixture Content

    Initial authored content.
    """.trimIndent() + "\n",
  )
  return repoRoot
}

private fun assertWrapperCommandRegenerates(command: String, tempDir: Path, context: CliRuntimeContext) {
  val skillName = "bill-$command-fixture"
  val repoRoot = authoringFixtureRepo(tempDir.resolve("$command-repo"), skillName)
  val payload =
    runJson(
      listOf(
        command,
        "--repo-root",
        repoRoot.toString(),
        "--skill-name",
        skillName,
        "--format",
        "json",
      ),
      context,
    )

  assertEquals(1, payload["regenerated_count"])
  assertEquals(true, payload["validator_ran"])
}

private fun assertEditBodyFileUpdatesContent(tempDir: Path, context: CliRuntimeContext) {
  val repoRoot = authoringFixtureRepo(tempDir.resolve("edit-repo"), "bill-edit-fixture")
  val bodyFile = tempDir.resolve("edit-body.md")
  Files.writeString(bodyFile, "Edited body from a file.")
  val payload =
    runJson(
      listOf(
        "edit",
        "bill-edit-fixture",
        "--repo-root",
        repoRoot.toString(),
        "--body-file",
        bodyFile.toString(),
        "--format",
        "json",
      ),
      context,
    )

  assertEquals(false, payload["used_editor"])
  assertEquals("complete", payload["completion_status"])
  assertEquals(true, payload["validator_ran"])
}

private fun assertFillBodyUpdatesContent(tempDir: Path, context: CliRuntimeContext) {
  val repoRoot = authoringFixtureRepo(tempDir.resolve("fill-repo"), "bill-fill-fixture")
  val payload =
    runJson(
      listOf(
        "fill",
        "bill-fill-fixture",
        "--repo-root",
        repoRoot.toString(),
        "--body",
        "Filled body from inline CLI input.",
        "--format",
        "json",
      ),
      context,
    )

  assertEquals("complete", payload["completion_status"])
  assertEquals(true, payload["validator_ran"])
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}
