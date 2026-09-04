package skillbill.nativeagent

import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.install.nativeagent.InstallNativeAgentPlatformPackLoader
import skillbill.nativeagent.composition.NativeAgentCompositionContext
import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.resolveNativeAgentCompositionTarget
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.scaffold.authoring.renderAuthoredContentBody
import java.nio.file.Path

fun testNativeAgentCompositionContext(repoRoot: Path): NativeAgentCompositionContext {
  val normalizedRoot = repoRoot.toAbsolutePath().normalize()
  val budget = runCatching {
    FileSystemRepoLocalConfig(NoopRuntimeDiagnostics)
      .readRepoLocalConfig(ReadRepoLocalConfigRequest(normalizedRoot))
      .config
      .reviewContextBudget
      .maxLaneLaunchBytes
  }.getOrDefault(RepoLocalConfig.defaults().reviewContextBudget.maxLaneLaunchBytes)
  return NativeAgentCompositionContext(
    reviewContextBudgetBytes = budget,
    renderGovernedBody = ::renderAuthoredContentBody,
    packLoader = InstallNativeAgentPlatformPackLoader,
  )
}

fun testComposeNativeAgentSource(repoRoot: Path, source: NativeAgentSource): NativeAgentSource {
  val context = testNativeAgentCompositionContext(repoRoot)
  return composeNativeAgentSource(
    repoRoot,
    source,
    context.reviewContextBudgetBytes,
    context.renderGovernedBody,
    context.packLoader,
  )
}

fun testResolveNativeAgentCompositionTarget(repoRoot: Path, source: NativeAgentSource): NativeAgentCompositionTarget? {
  val context = testNativeAgentCompositionContext(repoRoot)
  return resolveNativeAgentCompositionTarget(repoRoot, source, context.packLoader)
}
