package skillbill.cli

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliAuthoringParityTest {
  @Test
  fun `python-backed authoring inspection commands stay available through the kotlin cli`() {
    val repoRoot = outerRepoRoot()
    val tempDir = Files.createTempDirectory("skillbill-cli-python-backed")
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
  fun `python-backed authoring validation commands stay available through the kotlin cli`() {
    val repoRoot = outerRepoRoot()
    val tempDir = Files.createTempDirectory("skillbill-cli-python-backed-validation")
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
    val doctorSkill =
      runJson(
        listOf(
          "doctor",
          "skill",
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

    assertEquals("pass", validated["status"])
    assertEquals("pass", doctorSkill["status"])
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
    if (Files.isDirectory(current.resolve("skill_bill")) && Files.isDirectory(current.resolve("runtime-kotlin"))) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repository root from ${Path.of("").toAbsolutePath().normalize()}")
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}
