package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.IdeStatusSchemaValidator
import skillbill.db.core.DatabaseRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliWorkStatusTest {
  @Test
  fun `work status emits schema-valid idle json for a git repo with empty database`() {
    val fixture = gitRepoFixture("skillbill-cli-work-status-idle")
    val dbPath = fixture.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
      context = CliRuntimeContext(
        environment = emptyMap(),
        userHome = Files.createTempDirectory("skillbill-cli-work-status-home"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("idle", payload["lifecycle_state"])
    assertEquals("no_matching_work", (payload["problem"] as Map<*, *>)["code"])
    IdeStatusSchemaValidator.validate(payload, "cli-idle")
    assertFalse(result.stdout.contains("Exception"))
    assertFalse(result.stdout.contains("at skillbill"))
  }

  @Test
  fun `work status reports absent database as schema-valid json`() {
    val fixture = gitRepoFixture("skillbill-cli-work-status-absent-db")
    val missingDb = fixture.resolve("missing-metrics.db")

    val result = CliRuntime.run(
      listOf("--db", missingDb.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
      context = CliRuntimeContext(
        environment = emptyMap(),
        userHome = Files.createTempDirectory("skillbill-cli-work-status-home-absent"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("absent_database", (payload["problem"] as Map<*, *>)["code"])
    IdeStatusSchemaValidator.validate(payload, "cli-absent-db")
  }

  @Test
  fun `work status rejects invalid repository root with typed problem json`() {
    val missing = Files.createTempDirectory("skillbill-cli-work-status-missing").resolve("no-such-repo")
    val dbPath = Files.createTempDirectory("skillbill-cli-work-status-invalid-db").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "work", "status", "--repo-root", missing.toString(), "--format", "json"),
    )

    assertEquals(1, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("invalid_repository_input", (payload["problem"] as Map<*, *>)["code"])
    IdeStatusSchemaValidator.validate(payload, "cli-invalid-root")
    assertFalse(result.stdout.contains("\tat "))
  }

  @Test
  fun `work status performs no database writes`() {
    val fixture = gitRepoFixture("skillbill-cli-work-status-readonly")
    val dbPath = fixture.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    val before = Files.readAllBytes(dbPath)

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
      context = CliRuntimeContext(
        environment = emptyMap(),
        userHome = Files.createTempDirectory("skillbill-cli-work-status-home-ro"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(before.contentEquals(Files.readAllBytes(dbPath)))
  }

  @Test
  fun `work status requires repo-root`() {
    val result = CliRuntime.run(listOf("work", "status", "--format", "json"))
    assertTrue(result.exitCode != 0, result.stdout)
  }

  private fun gitRepoFixture(prefix: String): Path {
    val root = Files.createTempDirectory(prefix)
    Files.createDirectory(root.resolve(".git"))
    return root
  }
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
}
