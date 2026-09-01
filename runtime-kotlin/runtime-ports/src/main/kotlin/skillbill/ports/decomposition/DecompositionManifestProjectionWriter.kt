package skillbill.ports.decomposition

import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestWriteResult
import java.nio.file.Path

interface DecompositionManifestProjectionWriter {
  fun writeProjectionFromWorkflowState(
    repoRoot: Path,
    artifactsJson: String,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestFileStore,
  ): DecompositionManifestWriteResult?
}
