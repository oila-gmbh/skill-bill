package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusRequest
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/**
 * SKILL-77 Subtask 4: gates baseline status to installed-workspace sessions. Only when the
 * open [workspaceRoot] is the located installed workspace does it consult the runtime
 * [InstalledWorkspaceBaselineStatusPort]; clone sessions short-circuit to an empty set so
 * baseline indicators never appear there (AC4).
 */
@Inject
@SingleIn(UserScope::class)
class JvmInstalledWorkspaceBaselineService(
  private val locator: InstalledWorkspaceLocator,
  private val runtimeServices: DesktopRuntimeApplicationServices,
) : InstalledWorkspaceBaselineService {

  // Test seam: swap the runtime port without constructing a real RuntimeComponent graph.
  internal var statusPort: InstalledWorkspaceBaselineStatusPort? = null

  private val resolvedStatusPort: InstalledWorkspaceBaselineStatusPort
    get() = statusPort ?: runtimeServices.installedWorkspaceBaselineStatusPort

  override fun modifiedSkillRelativePaths(workspaceRoot: String): Set<String> {
    val availability = locator.locate()
    if (!availability.availability) return emptySet()
    val installRoot = Path.of(availability.path).toAbsolutePath().normalize()
    val openRoot = Path.of(workspaceRoot).toAbsolutePath().normalize()
    if (openRoot != installRoot) return emptySet()

    return resolvedStatusPort.modifiedSkillRelativePaths(
      InstalledWorkspaceBaselineStatusRequest(
        installRoot = installRoot,
        installHome = installRoot.parent,
      ),
    ).modifiedSkillRelativePaths
  }
}
