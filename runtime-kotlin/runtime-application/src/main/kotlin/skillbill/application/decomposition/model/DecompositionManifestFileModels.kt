package skillbill.application.decomposition.model

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence
import java.nio.file.Path

data class LoadedDecompositionManifest(
  val manifest: DecompositionManifest,
  val yamlText: String,
  val repairEvidence: DecompositionManifestRepairEvidence?,
)

data class ValidatedDecompositionManifestYaml(
  val manifest: DecompositionManifest,
  val yamlText: String,
  val repairEvidence: DecompositionManifestRepairEvidence?,
)

data class PreparedDecompositionManifestWrite(
  val manifestPath: Path,
  val manifest: DecompositionManifest,
  val yaml: String,
  val repairEvidence: List<DecompositionManifestRepairEvidence> = emptyList(),
)
