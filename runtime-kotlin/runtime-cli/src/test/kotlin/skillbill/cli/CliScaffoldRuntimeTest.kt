package skillbill.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliScaffoldRuntimeTest {
  @Test
  fun `new skill and new alias share the scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-new")
    val context = scaffoldPayloadContext(tempDir)

    val newSkillResult = scaffoldResult("new-skill", context)
    val newAliasPayload = scaffoldPayload("new", context)

    assertNewSkillScaffoldGolden(newSkillResult)
    assertEquals("ok", newAliasPayload.stringValue("status"))
    assertTrue(newAliasPayload.stringValue("skill_path").endsWith("/skills/bill-horizontal-kotlin"))
  }

  @Test
  fun `create-and-fill dry run preserves scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-fill")
    val bodyFile = tempDir.resolve("content-body.md")
    Files.writeString(bodyFile, "Authored native file content.")
    val context =
      CliRuntimeContext(
        stdinText =
        """
        {
          "scaffold_payload_version": "1.0",
          "kind": "horizontal",
          "name": "bill-horizontal-fill"
        }
        """.trimIndent(),
        userHome = tempDir,
      )

    assertCreateAndFillNativePayloads(context, bodyFile)
  }

  @Test
  fun `create-and-fill rejects multi artifact scaffold kinds with json diagnostics`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-fill-reject")
    listOf(
      """
      {
        "scaffold_payload_version": "1.0",
        "kind": "platform-pack",
        "platform": "java"
      }
      """.trimIndent(),
      """
      {
        "scaffold_payload_version": "1.0",
        "kind": "add-on",
        "platform": "kotlin",
        "name": "review-helper"
      }
      """.trimIndent(),
    ).forEach { stdin ->
      val result =
        CliRuntime.run(
          listOf("create-and-fill", "--payload", "-", "--body", "Authored content.", "--dry-run", "--format", "json"),
          CliRuntimeContext(stdinText = stdin, userHome = tempDir),
        )
      val payload = decodeJsonObject(result.stdout)

      assertEquals(1, result.exitCode)
      assertEquals("error", payload.stringValue("status"))
      assertContains(payload.stringValue("error"), "one content-managed skill")
    }
  }

  @Test
  fun `new addon dry run preserves scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-addon")
    val bodyFile = tempDir.resolve("addon-body.md")
    Files.writeString(bodyFile, "Pack-owned file guidance.")
    val payload =
      scaffoldPayload(
        listOf(
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-new-addon",
          "--body",
          "Pack-owned guidance.",
          "--dry-run",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )
    val bodyFilePayload =
      scaffoldPayload(
        listOf(
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-new-addon-file",
          "--body-file",
          bodyFile.toString(),
          "--dry-run",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )

    assertAddonScaffoldPayload(payload)
    assertAddonScaffoldPayload(bodyFilePayload)
    assertNativeBodyConflictErrors(
      listOf("new-addon", "--platform", "kmp", "--name", "android-conflict-addon"),
      CliRuntimeContext(userHome = tempDir),
    )
  }

  @Test
  fun `native scaffold payload errors are reported as structured cli errors`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-errors")
    val result =
      CliRuntime.run(
        listOf("new-skill", "--payload", "-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = """{"scaffold_payload_version":"9.99","kind":"horizontal"}""",
          userHome = tempDir,
        ),
      )
    val payload = decodeJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("error", payload.stringValue("status"))
    assertContains(payload.stringValue("error"), "scaffold_payload_version")
  }

  @Test
  fun `scaffold payload commands use native bridge`() {
    val scaffoldSource = Files.readString(Path.of("src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt"))
    val nativeScaffoldPayloadSource =
      commandBlock(
        scaffoldSource,
        "private fun runNativeScaffoldPayload",
        "private fun createAndFillResult",
      )
    val createAndFillNativeSource =
      commandBlock(
        scaffoldSource,
        "private fun createAndFillResult",
        "private fun errorResult",
      )

    assertFalse(scaffoldSource.contains("runPythonCli"), scaffoldSource)
    assertFalse(scaffoldSource.contains("runPythonScaffoldCli"), scaffoldSource)
    assertFalse(scaffoldSource.contains("pythonProcess"), scaffoldSource)
    listOf(
      commandBlock(scaffoldSource, "class NewSkillCommand", "class NewCommand"),
      commandBlock(scaffoldSource, "class NewCommand", "class CreateAndFillCommand"),
      commandBlock(scaffoldSource, "class NewAddonCommand", "class InstallAgentPathCommand"),
    ).forEach { commandSource ->
      assertContains(commandSource, "runNativeScaffoldPayload")
      assertFalse(commandSource.contains("runPythonScaffoldCli"), commandSource)
    }
    val createAndFillCommandSource = commandBlock(scaffoldSource, "class CreateAndFillCommand", "class NewAddonCommand")
    assertContains(createAndFillCommandSource, "createAndFillResult")
    assertFalse(createAndFillCommandSource.contains("runPythonScaffoldCli"), createAndFillCommandSource)
    assertContains(createAndFillNativeSource, "runNativeScaffoldPayload")
    assertFalse(createAndFillNativeSource.contains("runPythonScaffoldCli"), createAndFillNativeSource)
    assertFalse(nativeScaffoldPayloadSource.contains("runPythonCli"), nativeScaffoldPayloadSource)
    assertFalse(nativeScaffoldPayloadSource.contains("runPythonScaffoldCli"), nativeScaffoldPayloadSource)
    assertFalse(nativeScaffoldPayloadSource.contains("pythonProcess"), nativeScaffoldPayloadSource)
  }
}

private fun assertNewSkillScaffoldGolden(result: CliExecutionResult) {
  val payload = decodeJsonObject(result.stdout)
  val sessionId = payload.stringValue("session_id")
  val skillPath = payload.stringValue("skill_path")

  assertMatchesPattern(Regex("""^nss-\d{8}-[0-9a-f]{4}$"""), sessionId, "session_id")
  assertTrue(Path.of(skillPath).isAbsolute, "Expected absolute skill_path but got $skillPath")
  assertTrue(skillPath.endsWith("/skills/bill-horizontal-kotlin"))
  assertEquals(
    goldenJson(
      "cli-new-skill-scaffold-dry-run.json",
      "<SESSION_ID>" to sessionId,
      "<SKILL_PATH>" to skillPath,
    ),
    result.stdout,
  )
  assertEquals("ok", payload.stringValue("status"))
}

private fun assertCreateAndFillNativePayloads(context: CliRuntimeContext, bodyFile: Path) {
  val payload =
    scaffoldPayload(
      listOf(
        "create-and-fill",
        "--payload",
        "-",
        "--body",
        "Authored native content.",
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
  val bodyFilePayload =
    scaffoldPayload(
      listOf(
        "create-and-fill",
        "--payload",
        "-",
        "--body-file",
        bodyFile.toString(),
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
  val editorResult =
    CliRuntime.run(
      listOf("create-and-fill", "--payload", "-", "--editor", "--dry-run", "--format", "json"),
      context,
    )
  val editorPayload = decodeJsonObject(editorResult.stdout)

  assertEquals("ok", payload.stringValue("status"))
  assertTrue(payload.stringValue("skill_path").endsWith("/skills/bill-horizontal-fill"))
  assertEquals("ok", bodyFilePayload.stringValue("status"))
  assertTrue(bodyFilePayload.stringValue("skill_path").endsWith("/skills/bill-horizontal-fill"))
  assertEquals(1, editorResult.exitCode)
  assertEquals("unsupported", editorPayload.stringValue("status"))
  assertContains(editorPayload.stringValue("error"), "--payload --editor is not supported")
  assertNativeBodyConflictErrors(
    listOf("create-and-fill", "--payload", "-"),
    context,
  )
}

private fun assertAddonScaffoldPayload(payload: JsonObject) {
  assertEquals("ok", payload.stringValue("status"))
  assertTrue(
    Path.of(payload.stringValue("skill_path"))
      .endsWith(Path.of("platform-packs", "kmp", "addons")),
  )
}

private fun assertNativeBodyConflictErrors(prefix: List<String>, context: CliRuntimeContext) {
  val result =
    CliRuntime.run(
      prefix + listOf(
        "--body",
        "inline body",
        "--body-file",
        "-",
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
  val payload = decodeJsonObject(result.stdout)

  assertEquals(1, result.exitCode)
  assertEquals("error", payload.stringValue("status"))
  assertEquals("--body and --body-file are mutually exclusive.", payload.stringValue("error"))
}

private fun scaffoldPayloadContext(tempDir: Path): CliRuntimeContext = CliRuntimeContext(
  stdinText =
  """
        {
          "scaffold_payload_version": "1.0",
          "kind": "horizontal",
          "name": "bill-horizontal-kotlin"
        }
  """.trimIndent(),
  userHome = tempDir,
)

private fun scaffoldPayload(command: String, context: CliRuntimeContext): JsonObject =
  scaffoldPayload(listOf(command, "--payload", "-", "--dry-run", "--format", "json"), context)

private fun scaffoldPayload(arguments: List<String>, context: CliRuntimeContext): JsonObject {
  val result = scaffoldResult(arguments, context)
  return decodeJsonObject(result.stdout)
}

private fun scaffoldResult(command: String, context: CliRuntimeContext): CliExecutionResult =
  scaffoldResult(listOf(command, "--payload", "-", "--dry-run", "--format", "json"), context)

private fun scaffoldResult(arguments: List<String>, context: CliRuntimeContext): CliExecutionResult {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return result
}

private fun decodeJsonObject(rawJson: String): JsonObject {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  return parsed
}

private fun JsonObject.stringValue(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun commandBlock(source: String, startMarker: String, endMarker: String): String {
  val startIndex = source.indexOf(startMarker)
  val endIndex = source.indexOf(endMarker, startIndex + startMarker.length)
  require(startIndex >= 0) { "Missing source marker: $startMarker" }
  require(endIndex > startIndex) { "Missing source marker: $endMarker" }
  return source.substring(startIndex, endIndex)
}

private fun goldenJson(fileName: String, vararg replacements: Pair<String, String>): String {
  var expected = Files.readString(Path.of("src/test/resources/golden").resolve(fileName)).trim()
  replacements.forEach { (placeholder, value) ->
    expected = expected.replace(placeholder, value)
  }
  return expected
}

private fun assertMatchesPattern(pattern: Regex, value: String, label: String) {
  assertTrue(pattern.matches(value), "Expected $label to match ${pattern.pattern}, got $value")
}
