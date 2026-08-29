package skillbill.install.nativeagent

import skillbill.install.model.AgentTarget
import skillbill.nativeagent.rendering.NativeAgentProvider
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
