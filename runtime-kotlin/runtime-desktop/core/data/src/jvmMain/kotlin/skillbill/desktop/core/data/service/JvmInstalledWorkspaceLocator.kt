package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path

@Inject
@SingleIn(UserScope::class)
class JvmInstalledWorkspaceLocator : InstalledWorkspaceLocator {
  internal var homeProvider: () -> Path = { Path.of(System.getProperty("user.home")) }

  override fun locate(): InstalledWorkspaceAvailability {
    val root = homeProvider().resolve(".skill-bill").toAbsolutePath().normalize()
    val available = Files.isDirectory(root.resolve("skills")) || Files.isDirectory(root.resolve("platform-packs"))
    return InstalledWorkspaceAvailability(root.toString(), available)
  }
}
