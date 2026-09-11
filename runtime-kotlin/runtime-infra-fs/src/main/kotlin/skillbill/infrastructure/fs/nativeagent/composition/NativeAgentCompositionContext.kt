package skillbill.infrastructure.fs.nativeagent.composition

import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentPlatformPackLoader
import java.nio.file.Path

data class NativeAgentCompositionContext(
  val reviewContextBudgetBytes: Long,
  val renderGovernedBody: (Path, String) -> String,
  val packLoader: NativeAgentPlatformPackLoader,
)
