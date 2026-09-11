package skillbill.workflow.decomposition.runtime.model

import skillbill.model.FileLocation
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence

data class DecompositionManifestWriteResult(
  val manifestPath: FileLocation,
  val manifest: DecompositionManifest,
  val repairEvidence: List<DecompositionManifestRepairEvidence> = emptyList(),
)
