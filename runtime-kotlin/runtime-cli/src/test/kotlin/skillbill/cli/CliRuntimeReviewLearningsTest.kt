package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliRuntimeReviewLearningsTest {
  @Test
  fun `import-review emits stable json payload`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-import")
    val dbPath = tempDir.resolve("metrics.db")
    val reviewPath = tempDir.resolve("review.txt")
    Files.writeString(reviewPath, SAMPLE_REVIEW.trimIndent() + "\n")

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          dbPath.toString(),
          "import-review",
          reviewPath.toString(),
          "--format",
          "json",
        ),
        CliRuntimeContext(),
      )

    val payload = decodeJsonObject(result.stdout)
    assertEquals(0, result.exitCode)
    assertEquals(
      goldenJson("cli-import-review.json", "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString()),
      result.stdout,
    )
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), payload["db_path"])
    assertEquals("rvw-20260402-001", payload["review_run_id"])
    assertEquals("rvs-20260402-001", payload["review_session_id"])
    assertEquals(2, payload["finding_count"])
    assertEquals("bill-kotlin-code-review", payload["routed_skill"])
  }

  @Test
  fun `verified native json commands match golden fixtures`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-native-golden")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "off")
    val context =
      CliRuntimeContext(
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
        userHome = tempDir,
      )

    val version = CliRuntime.run(listOf("version", "--format", "json"), context)
    assertEquals(0, version.exitCode, version.stdout)
    assertEquals(goldenJson("cli-version.json", "<VERSION>" to INSTALLED_VERSION), version.stdout)

    val doctor = CliRuntime.run(listOf("--db", dbPath.toString(), "doctor", "--format", "json"), context)
    assertEquals(0, doctor.exitCode, doctor.stdout)
    assertEquals(
      goldenJson(
        "cli-doctor.json",
        "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString(),
        "<VERSION>" to INSTALLED_VERSION,
      ),
      doctor.stdout,
    )

    assertNativeReviewGolden(dbPath, context)
    assertNativeLearningGolden(tempDir, context)
    assertNativeVerifyWorkflowGolden(dbPath, context)
  }

  @Test
  fun `triage text output mirrors numbered decision lines`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-triage")
    val dbPath = tempDir.resolve("metrics.db")

    CliRuntime.run(
      listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
      CliRuntimeContext(stdinText = SAMPLE_REVIEW.trimIndent()),
    )

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          dbPath.toString(),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "1 fix - patched",
          "--decision",
          "2 reject - intentional",
        ),
        CliRuntimeContext(),
      )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "review_run_id: rvw-20260402-001")
    assertContains(result.stdout, "1. F-001 -> fix_applied | note: patched")
    assertContains(result.stdout, "2. F-002 -> fix_rejected | note: intentional")

    val jsonPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "triage",
        "--run-id",
        "rvw-20260402-001",
        "--decision",
        "1 accept - json parity",
        "--format",
        "json",
      )
    val recorded = jsonPayload["recorded"] as List<*>
    assertEquals("F-001", (recorded.first() as Map<*, *>)["finding_id"])
    assertEquals("finding_accepted", (recorded.first() as Map<*, *>)["outcome_type"])
  }

  @Test
  fun `review commands cover list stats feedback and aliases`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-review")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "anonymous")
    val context =
      CliRuntimeContext(
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
        userHome = tempDir,
      )

    importSampleReview(dbPath, context)

    val listPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "triage",
        "--run-id",
        "rvw-20260402-001",
        "--list",
        "--format",
        "json",
        context = context,
      )
    assertEquals(2, (listPayload["findings"] as List<*>).size)

    val feedbackPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "record-feedback",
        "--run-id",
        "rvw-20260402-001",
        "--event",
        "fix_applied",
        "--finding",
        "F-001",
        "--format",
        "json",
        context = context,
      )
    assertEquals("fix_applied", feedbackPayload["outcome_type"])
    assertEquals(1, feedbackPayload["recorded_findings"])

    assertReviewStatsPayload(dbPath, context)
    assertFeatureStatsAliases(dbPath, context)
  }

  @Test
  fun `learnings resolve text output preserves scope summary`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-learnings")
    val dbPath = tempDir.resolve("metrics.db")
    seedLearningScenario(dbPath)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          dbPath.toString(),
          "learnings",
          "resolve",
          "--skill",
          "bill-kotlin-code-review",
        ),
        CliRuntimeContext(),
      )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "scope_precedence: skill > repo > global")
    assertContains(result.stdout, "skill_name: bill-kotlin-code-review")
    assertContains(result.stdout, "applied_learnings: L-001")
    assertContains(
      result.stdout,
      "- [L-001] skill:bill-kotlin-code-review | Keep wording aligned | " +
        "Update the installer prompt when routing text changes.",
    )

    val jsonPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "learnings",
        "resolve",
        "--skill",
        "bill-kotlin-code-review",
        "--format",
        "json",
      )
    assertEquals("bill-kotlin-code-review", jsonPayload["skill_name"])
    assertEquals("L-001", jsonPayload["applied_learnings"])
  }

  @Test
  fun `learnings commands cover lifecycle mutations and validation`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-learnings-lifecycle")
    val dbPath = tempDir.resolve("metrics.db")
    seedLearningScenario(dbPath)

    val listPayload = runJson("--db", dbPath.toString(), "learnings", "list", "--format", "json")
    assertEquals(1, (listPayload["learnings"] as List<*>).size)

    val showPayload = runJson("--db", dbPath.toString(), "learnings", "show", "--id", "1", "--format", "json")
    assertEquals("Keep wording aligned", showPayload["title"])
    assertEquals("active", showPayload["status"])

    val editPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "learnings",
        "edit",
        "--id",
        "1",
        "--title",
        "Keep installer wording aligned",
        "--format",
        "json",
      )
    assertEquals("Keep installer wording aligned", editPayload["title"])

    val disabledPayload = runJson("--db", dbPath.toString(), "learnings", "disable", "--id", "1", "--format", "json")
    assertEquals("disabled", disabledPayload["status"])

    val enabledPayload = runJson("--db", dbPath.toString(), "learnings", "enable", "--id", "1", "--format", "json")
    assertEquals("active", enabledPayload["status"])

    val editWithoutFields =
      CliRuntime.run(listOf("--db", dbPath.toString(), "learnings", "edit", "--id", "1", "--format", "json"))
    assertEquals(1, editWithoutFields.exitCode)
    assertContains(editWithoutFields.stdout, "Learning edit requires at least one field")

    val deletePayload = runJson("--db", dbPath.toString(), "learnings", "delete", "--id", "1", "--format", "json")
    assertEquals(1, deletePayload["deleted_learning_id"])

    val emptyListResult = CliRuntime.run(listOf("--db", dbPath.toString(), "learnings", "list"))
    assertEquals(0, emptyListResult.exitCode)
    assertEquals("No learnings found.\n", emptyListResult.stdout)
  }
}
