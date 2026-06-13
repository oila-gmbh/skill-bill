package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.plan.InstallContext
import skillbill.install.plan.detectAgents
import skillbill.install.plan.installSkill
import skillbill.install.plan.uninstallTargets
import skillbill.ports.scaffold.install.ScaffoldInstallLinkPort
import skillbill.ports.scaffold.install.model.ScaffoldInstallLinkRequest
import skillbill.ports.scaffold.install.model.ScaffoldInstallLinkResult
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.nio.file.Files
import java.nio.file.Path

/**
 * Filesystem adapter for [ScaffoldInstallLinkPort]. Owns the agent detection +
 * per-skill `installSkill` call sequence the legacy `performInstall` helper uses inside
 * `ScaffoldService.kt`. The platform-pack manifest discovery hoist (F-015) is preserved so a
 * multi-skill platform-pack scaffold does not re-walk `platform-packs` once per skill.
 */
@Inject
class FileSystemScaffoldInstallLink : ScaffoldInstallLinkPort {
  override fun applyInstallLinks(request: ScaffoldInstallLinkRequest): ScaffoldInstallLinkResult {
    val agents = detectAgents()
    val packsRoot = request.repoRoot.resolve("platform-packs")
    val manifests = if (Files.isDirectory(packsRoot)) discoverPlatformPackManifests(packsRoot) else emptyList()
    val context = InstallContext(repoRoot = request.repoRoot, manifests = manifests)
    val targets = request.installPaths.flatMap { installPath ->
      installSkill(installPath, agents, context = context)
    }
    return ScaffoldInstallLinkResult(installTargets = targets)
  }

  override fun rollbackInstallTargets(installTargets: List<Path>) {
    uninstallTargets(installTargets)
  }
}
