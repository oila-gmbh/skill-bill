package skillbill.workflow.decomposition.runtime.model

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence
import java.nio.file.Path

data class DecompositionManifestWriteResult(
  val manifestPath: Path,
  val manifest: DecompositionManifest,
  val repairEvidence: List<DecompositionManifestRepairEvidence> = emptyList(),
)
