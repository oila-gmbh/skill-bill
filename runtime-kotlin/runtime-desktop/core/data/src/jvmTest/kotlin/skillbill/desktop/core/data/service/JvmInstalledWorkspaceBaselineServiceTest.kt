package skillbill.desktop.core.data.service

import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusRequest
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmInstalledWorkspaceBaselineServiceTest {
  private class StubLocator(private val availability: InstalledWorkspaceAvailability) : InstalledWorkspaceLocator {
    override fun locate(): InstalledWorkspaceAvailability = availability
  }

  private class RecordingStatusPort(private val result: Set<String>) : InstalledWorkspaceBaselineStatusPort {
    val requests = mutableListOf<InstalledWorkspaceBaselineStatusRequest>()
    override fun modifiedSkillRelativePaths(
      request: InstalledWorkspaceBaselineStatusRequest,
    ): InstalledWorkspaceBaselineStatusResult {
      requests += request
      return InstalledWorkspaceBaselineStatusResult(result)
    }
  }

  private fun service(
    locator: InstalledWorkspaceLocator,
    port: InstalledWorkspaceBaselineStatusPort,
  ): JvmInstalledWorkspaceBaselineService =
    JvmInstalledWorkspaceBaselineService(locator, DesktopRuntimeApplicationServices()).apply { statusPort = port }

  @Test
  fun `installed-root session delegates to the runtime status port`() {
    val installRoot = Files.createTempDirectory("installed-root")
    val locator = StubLocator(InstalledWorkspaceAvailability(path = installRoot.toString(), availability = true))
    val port = RecordingStatusPort(setOf("skills/bill-alpha"))

    val result = service(locator, port).modifiedSkillRelativePaths(installRoot)

    assertEquals(setOf("skills/bill-alpha"), result)
    assertEquals(installRoot.toAbsolutePath().normalize(), port.requests.single().installRoot)
    assertEquals(installRoot.toAbsolutePath().normalize().parent, port.requests.single().installHome)
  }

  @Test
  fun `clone session never consults the baseline port`() {
    val installRoot = Files.createTempDirectory("installed-root")
    val cloneRoot = Files.createTempDirectory("clone-root")
    val locator = StubLocator(InstalledWorkspaceAvailability(path = installRoot.toString(), availability = true))
    val port = RecordingStatusPort(setOf("skills/bill-alpha"))

    val result = service(locator, port).modifiedSkillRelativePaths(cloneRoot)

    assertEquals(emptySet(), result)
    assertTrue(port.requests.isEmpty())
  }

  @Test
  fun `unavailable installed workspace yields empty`() {
    val locator = StubLocator(InstalledWorkspaceAvailability(path = "", availability = false))
    val port = RecordingStatusPort(setOf("skills/bill-alpha"))

    val result = service(locator, port).modifiedSkillRelativePaths(Path.of("/tmp/whatever"))

    assertEquals(emptySet(), result)
    assertTrue(port.requests.isEmpty())
  }
}
