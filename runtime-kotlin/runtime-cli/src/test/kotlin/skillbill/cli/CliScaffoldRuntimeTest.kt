package skillbill.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
  fun `new platform pack dry run shows composition manifest preview`() {
    val repoRoot = compositionFixtureRepo()
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-composition")
    val platform = "cli-composition-${System.nanoTime()}"
    val result =
      CliRuntime.run(
        listOf("new", "--payload", "-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText =
          """
          {
            "scaffold_payload_version": "1.0",
            "kind": "platform-pack",
            "platform": "$platform",
            "repo_root": "$repoRoot",
            "skeleton_mode": "starter",
            "routing_signals": {
              "strong": ["$platform.marker"]
            },
            "baseline_layers": [
              {
                "platform": "kotlin",
                "skill": "bill-kotlin-code-review",
                "scope": "same-review-scope",
                "required": true,
                "mode": "kmp-baseline"
              }
            ]
          }
          """.trimIndent(),
          userHome = tempDir,
        ),
      )
    val payload = decodeJsonObject(result.stdout)
    val manifestPath = repoRoot.resolve("platform-packs/$platform/platform.yaml").toString()
    val preview =
      payload["manifest_edit_previews"]
        ?.jsonObject
        ?.get(manifestPath)
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertEquals("true", payload["dry_run"]?.jsonPrimitive?.contentOrNull)
    assertEquals(manifestPath, payload["manifest_edits"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
    assertContains(preview, "code_review_composition:")
    assertContains(preview, "baseline_layers:")
    assertContains(preview, "platform: \"kotlin\"")
    assertTrue(Files.notExists(repoRoot.resolve("platform-packs/$platform")))
  }

  @Test
  fun `show surfaces manifest declared review composition`() {
    val repoRoot = compositionFixtureRepo(kmpLayerRequired = false)
    val tempDir = Files.createTempDirectory("skillbill-cli-show-composition")
    val result =
      CliRuntime.run(
        listOf(
          "show",
          "bill-kmp-code-review",
          "--repo-root",
          repoRoot.toString(),
          "--content",
          "none",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )
    val payload = decodeJsonObject(result.stdout)
    val composition = payload["review_composition"]?.jsonObject
    val layer = composition?.get("baseline_layers")?.jsonArray?.single()?.jsonObject

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("platform.yaml", composition?.get("source")?.jsonPrimitive?.contentOrNull)
    assertEquals("kotlin", layer?.get("platform")?.jsonPrimitive?.contentOrNull)
    assertEquals("bill-kotlin-code-review", layer?.get("skill")?.jsonPrimitive?.contentOrNull)
    assertEquals("same-review-scope", layer?.get("scope")?.jsonPrimitive?.contentOrNull)
    assertEquals("false", layer?.get("required")?.jsonPrimitive?.contentOrNull)
    assertEquals("kmp-baseline", layer?.get("mode")?.jsonPrimitive?.contentOrNull)
    assertEquals(
      "Run 1 optional baseline layer(s) before pack-local specialists.",
      composition?.get("summary")?.jsonPrimitive?.contentOrNull,
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

private fun goldenJson(fileName: String, vararg replacements: Pair<String, String>): String {
  var expected = Files.readString(Path.of("src/test/resources/golden").resolve(fileName))
    .replace("\r\n", "\n")
    .trim()
  replacements.forEach { (placeholder, value) ->
    expected = expected.replace(placeholder, value)
  }
  return expected
}

private fun assertMatchesPattern(pattern: Regex, value: String, label: String) {
  assertTrue(pattern.matches(value), "Expected $label to match ${pattern.pattern}, got $value")
}

private fun compositionFixtureRepo(kmpLayerRequired: Boolean = true): Path {
  val repoRoot = Files.createTempDirectory("skillbill-cli-composition-repo")
  seedCompositionPack(repoRoot, "kotlin")
  seedCompositionPack(
    repoRoot = repoRoot,
    slug = "kmp",
    composition =
    """
    |code_review_composition:
    |  baseline_layers:
    |    - platform: "kotlin"
    |      skill: "bill-kotlin-code-review"
    |      scope: "same-review-scope"
    |      required: $kmpLayerRequired
    |      mode: "kmp-baseline"
    """.trimMargin(),
  )
  return repoRoot
}

private fun seedCompositionPack(repoRoot: Path, slug: String, composition: String = "") {
  val skillName = "bill-$slug-code-review"
  val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
  val skillRoot = packRoot.resolve("code-review").resolve(skillName)
  Files.createDirectories(skillRoot)
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    """
    |platform: "$slug"
    |contract_version: "1.1"
    |display_name: "$slug"
    |
    |routing_signals:
    |  strong:
    |    - "$slug.marker"
    |  tie_breakers: []
    |
    |declared_code_review_areas: []
    |
    |declared_files:
    |  baseline: "code-review/$skillName/content.md"
    |  areas: {}
    |area_metadata: {}
    |$composition
    """.trimMargin(),
  )
  Files.writeString(
    skillRoot.resolve("content.md"),
    """
    |---
    |name: $skillName
    |description: Fixture $slug code review.
    |---
    |
    |## Review Focus
    |
    |Review $slug changes with fixture guidance.
    |
    |## Review Guidance
    |
    |- Keep fixture behavior explicit.
    """.trimMargin(),
  )
}
