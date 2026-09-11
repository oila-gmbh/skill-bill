package skillbill.infrastructure.fs

import skillbill.infrastructure.fs.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.fs.nativeagent.composition.composeNativeAgentSource
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.infrastructure.fs.scaffold.authoring.renderAuthoredContentBody
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import java.nio.file.Path

object FileSystemNativeAgentComposition {
  fun composeNativeAgentSource(
    repoRoot: Path,
    source: NativeAgentSource,
    repoLocalConfigPort: RepoLocalConfigPort,
    packLoader: NativeAgentPlatformPackLoader = FileSystemNativeAgentPlatformPackLoader,
  ): NativeAgentSource {
    val normalizedRoot = repoRoot.toAbsolutePath().normalize()
    val budget = repoLocalConfigPort
      .readRepoLocalConfig(ReadRepoLocalConfigRequest(normalizedRoot))
      .config
      .reviewContextBudget
      .maxLaneLaunchBytes
    return composeNativeAgentSource(
      repoRoot = normalizedRoot,
      source = source,
      reviewContextBudgetBytes = budget,
      renderGovernedBody = ::renderAuthoredContentBody,
      packLoader = packLoader,
    )
  }
}
