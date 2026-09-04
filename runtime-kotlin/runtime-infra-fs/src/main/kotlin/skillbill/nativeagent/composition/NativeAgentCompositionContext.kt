package skillbill.nativeagent.composition

import skillbill.nativeagent.platformpack.NativeAgentPlatformPackLoader
import java.nio.file.Path

data class NativeAgentCompositionContext(
  val reviewContextBudgetBytes: Long,
  val renderGovernedBody: (Path, String) -> String,
  val packLoader: NativeAgentPlatformPackLoader,
)
