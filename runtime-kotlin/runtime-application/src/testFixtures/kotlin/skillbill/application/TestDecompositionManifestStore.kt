package skillbill.application

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.model.DecompositionManifestWriteResult
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

object TestDecompositionManifestStore : DecompositionManifestStore {
  override fun readText(path: Path): String = Files.readString(path)

  override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> {
    val featureSpecsRoot = repoRoot.resolve(".feature-specs")
    if (!Files.isDirectory(featureSpecsRoot)) return emptyList()
    return Files.walk(featureSpecsRoot).use { paths ->
      paths
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == DECOMPOSITION_MANIFEST_FILENAME }
        .toList()
    }
  }

  override fun listDirectChildDirectories(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.toList()
    }
  }

  override fun deleteIfExists(target: Path) {
    Files.deleteIfExists(target)
  }

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

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = YAMLMapper().writeValueAsString(wireMap)
}

fun loadDecompositionManifest(path: Path) = skillbill.application.decomposition.loadDecompositionManifest(
  path,
  TestDecompositionManifestStore,
  testDecompositionManifestValidator,
)

fun writeIfDecomposed(request: DecompositionManifestWriteRequest): DecompositionManifestWriteResult? =
  testDecompositionManifestWriter.writeIfDecomposed(
    request,
    testDecompositionManifestValidator,
    TestDecompositionManifestStore,
  )

fun writeFromWorkflowUpdate(
  repoRoot: Path,
  existingArtifactsJson: String,
  artifactsPatch: Map<String, Any?>?,
  runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
): DecompositionManifestWriteResult? = testDecompositionManifestWriter.writeFromWorkflowUpdate(
  DecompositionManifestWorkflowProjectionInput(
    repoRoot = repoRoot,
    existingArtifactsJson = existingArtifactsJson,
    validator = testDecompositionManifestValidator,
    artifactsPatch = artifactsPatch,
    runtimeUpdate = runtimeUpdate ?: DecompositionManifestRuntimeUpdate(),
    fileStore = TestDecompositionManifestStore,
  ),
)

fun writeProjectionFromWorkflowState(repoRoot: Path, artifactsJson: String): DecompositionManifestWriteResult? =
  testDecompositionManifestWriter.writeProjectionFromWorkflowState(
    repoRoot = repoRoot,
    artifactsJson = artifactsJson,
    validator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestStore,
  )
