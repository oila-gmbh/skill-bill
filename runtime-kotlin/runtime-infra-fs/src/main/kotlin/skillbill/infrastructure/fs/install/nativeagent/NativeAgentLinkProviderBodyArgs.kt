package skillbill.infrastructure.fs.install.nativeagent

import skillbill.infrastructure.fs.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.AgentTarget
import java.nio.file.Path

internal data class NativeAgentLinkProviderBodyArgs(
  val provider: NativeAgentProvider,
  val request: NativeAgentLinkRequest,
  val targets: List<AgentTarget>,
  val resolvedHome: Path,
  val cacheRoot: Path,
  val validationRoot: Path,
  val journal: ProviderMutationJournal,
)
