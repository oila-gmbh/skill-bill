package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.SkillBillVersion
import skillbill.cli.core.CliRuntime
import skillbill.cli.core.ExternalCommand
import skillbill.cli.core.ExternalCommandResult
import skillbill.cli.core.ExternalCommandRunner
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class CliRuntimeTelemetryTest {
  @Test
  fun `telemetry status and remote stats preserve payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "off")
    telemetryStatusPayload(dbPath, configPath)
    val (statsPayload, capturedRequests) = remoteStatsScenario(configPath)
    assertEquals(expectedRemoteStatsPayload(), statsPayload)
    assertEquals(expectedCliRemoteStatsRequests(), capturedRequests)
  }

  // SKILL-170 AC-001/AC-002/AC-004: queue depth and sync state are visible at every resolved level.
  @Test
  fun `telemetry status reports queue depth and sync state across outbox states`() {
    val empty = telemetryStatusState(level = "anonymous", pendingEvents = 0, priorSync = false)
    assertEquals(0, empty["pending_events"])
    assertEquals("never_synced", empty["last_sync_state"])
    assertNull(empty["last_synced_at"])

    val neverSynced = telemetryStatusState(level = "anonymous", pendingEvents = 2, priorSync = false)
    assertEquals(2, neverSynced["pending_events"])
    assertEquals("never_synced", neverSynced["last_sync_state"])
    assertNull(neverSynced["last_synced_at"])

    val syncedWithPending = telemetryStatusState(level = "anonymous", pendingEvents = 2, priorSync = true)
    assertEquals(2, syncedWithPending["pending_events"])
    assertEquals("synced", syncedWithPending["last_sync_state"])
    assertNotNull(syncedWithPending["last_synced_at"])

    val disabled = telemetryStatusState(level = "off", pendingEvents = 3, priorSync = true)
    assertEquals(false, disabled["telemetry_enabled"])
    assertEquals(3, disabled["pending_events"], "An off install must still show what is queued and undelivered.")
    assertEquals("synced", disabled["last_sync_state"])
    assertNull(disabled["install_id"], "An off install must not leak the install id.")
  }

  // The text formatter drops null values, so the distinction has to survive without last_synced_at.
  @Test
  fun `telemetry status renders the sync state distinction in text format`() {
    val neverSynced = telemetryStatusText(level = "anonymous", pendingEvents = 1, priorSync = false)
    assertContains(neverSynced, "pending_events: 1")
    assertContains(neverSynced, "last_sync_state: never_synced")

    val synced = telemetryStatusText(level = "anonymous", pendingEvents = 1, priorSync = true)
    assertContains(synced, "last_sync_state: synced")
    assertContains(synced, "last_synced_at: ")
  }

  // AC-003: proven negatively — the requester fails the test if status reaches for the network at all.
  @Test
  fun `telemetry status makes no network call and tolerates a missing database`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry-status-read")
    val dbPath = tempDir.resolve("metrics.db")
    writeTelemetryConfig(tempDir, level = "anonymous", proxyUrl = TELEMETRY_FIXTURE_PROXY_URL)

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "telemetry", "status", "--format", "json"),
        telemetryStatusContext(tempDir),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals(0, payload["pending_events"])
    assertEquals("never_synced", payload["last_sync_state"])
    assertFalse(Files.exists(dbPath), "telemetry status is a read: it must not create the database.")
  }

  @Test
  fun `telemetry local commands mutate config and sync disabled state`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry-local")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "off")
    val context = CliRuntimeContext(environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()))

    val enablePayload =
      runJson(
        listOf("--db", dbPath.toString(), "telemetry", "enable", "--level", "full", "--format", "json"),
        context,
      )
    assertEquals(true, enablePayload["telemetry_enabled"])
    assertEquals("full", enablePayload["telemetry_level"])

    val setLevelPayload =
      runJson(listOf("--db", dbPath.toString(), "telemetry", "set-level", "anonymous", "--format", "json"), context)
    assertEquals("anonymous", setLevelPayload["telemetry_level"])

    val disablePayload = runJson(listOf("--db", dbPath.toString(), "telemetry", "disable", "--format", "json"), context)
    assertEquals(false, disablePayload["telemetry_enabled"])
    assertEquals("off", disablePayload["telemetry_level"])

    val syncPayload = runJson(listOf("--db", dbPath.toString(), "telemetry", "sync", "--format", "json"), context)
    assertEquals("disabled", syncPayload["sync_status"])
    assertEquals("disabled", syncPayload["sync_target"])
  }

  @Test
  fun `telemetry capabilities uses configured requester`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry-capabilities")
    val configPath = writeTelemetryConfig(tempDir, "anonymous")
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      CliRuntimeContext(
        environment =
        mapOf(
          CONFIG_ENVIRONMENT_KEY to configPath.toString(),
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
          TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
        ),
        requester = statsRequester(capturedRequests),
      )

    val payload = runJson(listOf("telemetry", "capabilities", "--format", "json"), context)

    assertEquals(expectedCapabilitiesPayload(), payload)
    assertEquals(true, payload["supports_ingest"])
    assertEquals(true, payload["supports_stats"])
    assertEquals(listOf(expectedCliRemoteStatsRequests().first()), capturedRequests)
  }

}
