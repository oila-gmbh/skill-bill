package skillbill.ports.workflow.decomposition.runtime.model
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

data class DecompositionManifestFileCandidate(
  val path: Path,
  val manifest: DecompositionManifest,
)
