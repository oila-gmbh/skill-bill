package skillbill.application.updatecheck

import skillbill.application.model.RECOMMENDED_INSTALL_COMMAND
import skillbill.application.model.UpdateCheckStatus
import skillbill.application.system.SystemService
import skillbill.model.TransportContext
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckServiceTest {
  @Test
  fun `maps update available up to date and ahead of release`() {
    val update = service(responseBody = releases("v0.4.0")).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, update.status)
    assertEquals("0.3.0-SNAPSHOT", update.installedVersion)
    assertEquals("v0.4.0", update.latestVersion)
    assertEquals(RECOMMENDED_INSTALL_COMMAND, update.recommendedInstallCommand)

    val upToDate = service(responseBody = releases("v0.3.0-SNAPSHOT")).check(includePrereleases = true)
    assertEquals(UpdateCheckStatus.UP_TO_DATE, upToDate.status)
    assertNull(upToDate.recommendedInstallCommand)

    val sameBaseRelease = service(responseBody = releases("v0.3.0")).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.AHEAD_OF_RELEASE, sameBaseRelease.status)
    assertNull(sameBaseRelease.recommendedInstallCommand)

    val ahead = service(responseBody = releases("v0.2.0")).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.AHEAD_OF_RELEASE, ahead.status)
  }

  @Test
  fun `selects stable releases by default and prereleases when requested`() {
    val body = releases("v0.4.0-rc.1", "v0.3.0")

    val stable = service(responseBody = body).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.AHEAD_OF_RELEASE, stable.status)
    assertEquals("v0.3.0", stable.latestVersion)

    val prerelease = service(responseBody = body).check(includePrereleases = true)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, prerelease.status)
    assertEquals("v0.4.0-rc.1", prerelease.latestVersion)
  }

  @Test
  fun `maps soft failures to unknown`() {
    assertEquals(UpdateCheckStatus.UNKNOWN, service(responseBody = "not-json").check(false).status)
    assertEquals(UpdateCheckStatus.UNKNOWN, service(responseBody = "[]").check(false).status)
    assertEquals(UpdateCheckStatus.UNKNOWN, service(statusCode = 429, responseBody = "").check(false).status)
    assertEquals(UpdateCheckStatus.UNKNOWN, service(responseBody = releases("nonsense")).check(false).status)
  }

  private fun service(statusCode: Int = 200, responseBody: String): UpdateCheckService = UpdateCheckService(
    systemService = SystemService(TestDatabaseSessionFactory(), TestTelemetrySettingsProvider),
    transportContext = TransportContext(
      requester = HttpRequester { method, url, _, headers ->
        assertEquals("GET", method)
        assertEquals("https://api.github.com/repos/Sermilion/skill-bill/releases", url)
        assertEquals("skill-bill-update-check", headers["User-Agent"])
        HttpResponse(statusCode = statusCode, body = responseBody)
      },
    ),
  )
}

private fun releases(vararg tags: String): String = tags.joinToString(prefix = "[", postfix = "]") { tag ->
  val prerelease = tag.contains("-")
  """
      {
        "tag_name":"$tag",
        "prerelease":$prerelease,
        "draft":false,
        "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/$tag"
      }
  """.trimIndent()
}

private class TestDatabaseSessionFactory : DatabaseSessionFactory {
  private val dbPath = Files.createTempDirectory("skillbill-update-check-db").resolve("metrics.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbOverride?.let(Path::of) ?: dbPath
  override fun databaseExists(dbOverride: String?): Boolean = Files.exists(resolveDbPath(dbOverride))
  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = error("unused")
  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = error("unused")
}

private object TestTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = error("unused")
}
