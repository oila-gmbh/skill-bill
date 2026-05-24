package skillbill.application

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.application.model.DecompositionManifestWriteResult
import skillbill.ports.workflow.DecompositionManifestFileStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object TestDecompositionManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = Files.readString(path)

  override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

  override fun writeTextAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
    Files.writeString(temp, content)
    try {
      Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, target, REPLACE_EXISTING)
    }
  }
}

internal fun loadDecompositionManifest(path: Path) = loadDecompositionManifest(path, TestDecompositionManifestFileStore)

internal fun writeIfDecomposed(request: DecompositionManifestWriteRequest): DecompositionManifestWriteResult? =
  DecompositionManifestWriter.writeIfDecomposed(request, TestDecompositionManifestFileStore)

internal fun writeFromWorkflowUpdate(
  repoRoot: Path,
  existingArtifactsJson: String,
  artifactsPatch: Map<String, Any?>?,
  runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
): DecompositionManifestWriteResult? = DecompositionManifestWriter.writeFromWorkflowUpdate(
  repoRoot = repoRoot,
  existingArtifactsJson = existingArtifactsJson,
  artifactsPatch = artifactsPatch,
  runtimeUpdate = runtimeUpdate,
  fileStore = TestDecompositionManifestFileStore,
)

internal fun writeProjectionFromWorkflowState(
  repoRoot: Path,
  artifactsJson: String,
): DecompositionManifestWriteResult? = DecompositionManifestWriter.writeProjectionFromWorkflowState(
  repoRoot = repoRoot,
  artifactsJson = artifactsJson,
  fileStore = TestDecompositionManifestFileStore,
)
