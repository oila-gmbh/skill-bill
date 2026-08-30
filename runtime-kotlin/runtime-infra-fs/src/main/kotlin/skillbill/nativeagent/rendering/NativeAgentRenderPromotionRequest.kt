package skillbill.nativeagent.rendering

import java.nio.file.Path

internal data class NativeAgentRenderPromotionRequest(
  val providerRoot: Path,
  val staging: Path,
  val rendered: List<RenderedAgent>,
  val orphanCandidates: List<Path>,
  val beforeMutation: (Path) -> Unit,
  val provider: NativeAgentProvider,
  val cacheRoot: Path,
)
