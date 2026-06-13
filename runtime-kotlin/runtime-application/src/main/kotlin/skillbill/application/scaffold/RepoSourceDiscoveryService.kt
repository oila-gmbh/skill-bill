package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.repoRoot
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.model.NativeAgentSourceProjection
import java.nio.file.Path

@Inject
class RepoSourceDiscoveryService(
  private val gateway: RepoSourceDiscoveryGateway,
) {
  fun discoverGovernedAddonFiles(repoRoot: Path) = gateway.discoverGovernedAddonFiles(repoRoot)

  fun discoverGeneratedArtifactFiles(repoRoot: Path) = gateway.discoverGeneratedArtifactFiles(repoRoot)

  fun discoverNativeAgentSourceFiles(platformPacksRoot: Path, skillsRoot: Path?): List<Path> =
    gateway.discoverNativeAgentSourceFiles(platformPacksRoot, skillsRoot)

  fun parseNativeAgentSourceFile(path: Path): List<NativeAgentSourceProjection> =
    gateway.parseNativeAgentSourceFile(path)

  fun renderNativeAgentSource(source: NativeAgentSourceProjection): String = gateway.renderNativeAgentSource(source)

  fun renderComposedNativeAgentSource(repoRoot: Path, source: NativeAgentSourceProjection): String =
    gateway.renderComposedNativeAgentSource(repoRoot, source)
}
