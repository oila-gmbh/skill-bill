package skillbill.desktop.core.data.di

import me.tatarka.inject.annotations.Inject
import skillbill.application.InstallAgentService
import skillbill.application.InstallService
import skillbill.application.RepoSourceDiscoveryService
import skillbill.application.RepoValidationService
import skillbill.application.ScaffoldCatalogService
import skillbill.application.ScaffoldService
import skillbill.application.SkillRemoveService
import skillbill.desktop.core.common.di.UserScope
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.model.RuntimeContext
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.telemetry.TelemetryLevelMutator
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/**
 * UserScope bridge from the desktop composition graph into the runtime application layer.
 *
 * The desktop graph cannot include RuntimeComponent as a parent dependency without leaking the
 * runtime-core component through KMP data-module call sites and direct-construction tests. This is
 * the narrow composition-owned seam: it creates a component once, extracts application services,
 * and never exposes or caches the component itself in desktop data gateways.
 */
@Inject
@SingleIn(UserScope::class)
class DesktopRuntimeApplicationServices {
  private val services by lazy(LazyThreadSafetyMode.NONE) {
    buildDesktopRuntimeApplicationServices(currentUserHome())
  }

  val scaffoldService: ScaffoldService
    get() = services.scaffoldService

  val scaffoldCatalogService: ScaffoldCatalogService
    get() = services.scaffoldCatalogService

  val repoSourceDiscoveryService: RepoSourceDiscoveryService
    get() = services.repoSourceDiscoveryService

  val repoValidationService: RepoValidationService
    get() = services.repoValidationService

  val skillRemoveService: SkillRemoveService
    get() = services.skillRemoveService

  val installedWorkspaceBaselineStatusPort: InstalledWorkspaceBaselineStatusPort
    get() = services.installedWorkspaceBaselineStatusPort

  companion object {
    fun forCurrentUserHome(): DesktopRuntimeApplicationServices = DesktopRuntimeApplicationServices()
  }
}

internal data class DesktopRuntimeInstallServices(
  val installService: InstallService,
  val installAgentService: InstallAgentService,
  val installSelectionPersistencePort: InstallSelectionPersistencePort,
  val telemetryLevelMutator: TelemetryLevelMutator,
) {
  companion object {
    fun forHome(home: Path): DesktopRuntimeInstallServices {
      val component = runtimeComponentForHome(home)
      return DesktopRuntimeInstallServices(
        installService = component.installService,
        installAgentService = component.installAgentService,
        installSelectionPersistencePort = component.installSelectionPersistencePort,
        telemetryLevelMutator = component.telemetryLevelMutator,
      )
    }
  }
}

private data class DesktopRuntimeApplicationServiceBundle(
  val scaffoldService: ScaffoldService,
  val scaffoldCatalogService: ScaffoldCatalogService,
  val repoSourceDiscoveryService: RepoSourceDiscoveryService,
  val repoValidationService: RepoValidationService,
  val skillRemoveService: SkillRemoveService,
  val installedWorkspaceBaselineStatusPort: InstalledWorkspaceBaselineStatusPort,
)

private fun buildDesktopRuntimeApplicationServices(home: Path): DesktopRuntimeApplicationServiceBundle {
  val component = runtimeComponentForHome(home)
  return DesktopRuntimeApplicationServiceBundle(
    scaffoldService = component.scaffoldService,
    scaffoldCatalogService = component.scaffoldCatalogService,
    repoSourceDiscoveryService = component.repoSourceDiscoveryService,
    repoValidationService = component.repoValidationService,
    skillRemoveService = component.skillRemoveService,
    installedWorkspaceBaselineStatusPort = component.installedWorkspaceBaselineStatusPort,
  )
}

private fun runtimeComponentForHome(userHome: Path): RuntimeComponent = RuntimeComponent::class.create(
  RuntimeContext(
    environment = System.getenv(),
    userHome = userHome.toAbsolutePath().normalize(),
  ),
)

private fun currentUserHome(): Path = Path.of(System.getProperty("user.home"))
