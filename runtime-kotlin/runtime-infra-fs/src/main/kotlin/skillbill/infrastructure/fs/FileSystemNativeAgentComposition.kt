package skillbill.infrastructure.fs

import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.scaffold.authoring.renderAuthoredContentBody
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
