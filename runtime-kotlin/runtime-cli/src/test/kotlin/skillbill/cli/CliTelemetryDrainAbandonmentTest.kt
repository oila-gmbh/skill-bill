package skillbill.cli

import skillbill.cli.kernel.drainTelemetryOnCompletion
import skillbill.cli.model.CliRuntimeContext
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.ports.telemetry.RemoteTransportPort
import skillbill.ports.telemetry.model.RemoteTransportResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTelemetryDrainAbandonmentTest {
  /**
   * The drain's join timeout is 5s, so this case pays that wall clock deliberately: the bug it
   * guards is that an outbox which never flushes leaves no trace anywhere, and only the
   * abandonment path can show it. The same case pins the two constraints the fix must not break.
   */
  @Test
  fun `an abandoned drain records the degradation without printing to the run surfaces`() {
    val home = Files.createTempDirectory("skillbill-drain-abandon")
    val dbPath = home.resolve("drain.db")
    val blockingRequester = BlockingTelemetryRequester()
    val diagnostics = RecordingRuntimeDiagnostics()
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    materializeTelemetryDatabase(
      home,
      dbPath,
      level = "anonymous",
      context = drainContext(home, dbPath, RecordingTelemetryRequester(), StringBuilder(), StringBuilder()),
    )
    seedTelemetryOutbox(dbPath, "skillbill_fixture_event")
    val blockedContext = drainContext(home, dbPath, blockingRequester, stdout, stderr)
    val telemetryService = RuntimeComponent::class.create(blockedContext.toRuntimeContext()).telemetryService

    drainTelemetryOnCompletion(telemetryService, dbPath.toString(), diagnostics)

    assertTrue(diagnostics.warnings.any { it.contains("drain abandoned") }, diagnostics.warnings.toString())
    assertEquals("", stdout.toString())
    assertEquals("", stderr.toString())
    assertTrue(pendingTelemetryOutboxCount(dbPath) >= 1)
    blockingRequester.release()
  }

  private fun drainContext(
    home: Path,
    dbPath: Path,
    requester: RemoteTransportPort,
    stdout: StringBuilder,
    stderr: StringBuilder,
  ): CliRuntimeContext = CliRuntimeContext(
    dbPathOverride = dbPath.toString(),
    userHome = home,
    requester = requester,
    liveStdout = { stdout.append(it) },
    liveStderr = { stderr.append(it) },
  )
}

private class BlockingTelemetryRequester : RemoteTransportPort {
  private val gate = CountDownLatch(1)

  override fun execute(
    method: String,
    url: String,
    bodyJson: String?,
    headers: Map<String, String>,
  ): RemoteTransportResponse {
    gate.await(BLOCK_CEILING_SECONDS, TimeUnit.SECONDS)
    return RemoteTransportResponse(statusCode = 200, body = "{}")
  }

  fun release() = gate.countDown()

  private companion object {
    const val BLOCK_CEILING_SECONDS = 30L
  }
}
