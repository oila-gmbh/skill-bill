package skillbill.application

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.application.model.DecompositionManifestWriteResult
import skillbill.application.workflow.repoRoot
import skillbill.install.model.InstallPlanWireValidator
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.WorkflowSnapshotValidator
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object TestDecompositionManifestFileStore : DecompositionManifestFileStore {
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

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String =
    com.fasterxml.jackson.dataformat.yaml.YAMLMapper().writeValueAsString(wireMap)
}

/**
 * SKILL-52.3 subtask 1: pass-through decomposition validator port fake for
 * application tests. The concrete schema + coherence validators now live in
 * `runtime-infra-fs`, and `runtime-application` must not depend on infra-fs
 * (enforced by `RuntimeGradleModuleLayeringTest`). Application tests that
 * assert real schema/coherence loud-fails live in `runtime-core` test (the
 * composition layer that legitimately sees the infra-fs adapters); the
 * remaining application tests exercise codec/file roundtrips that do not
 * depend on schema enforcement, so a pass-through fake is sufficient here.
 */
internal val testDecompositionManifestValidator: DecompositionManifestValidator =
  object : DecompositionManifestValidator {
    override fun validate(manifest: Map<String, Any?>, sourceLabel: String) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?> =
      com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
        .readValue(yamlText, Map::class.java) as Map<String, Any?>
  }

/**
 * Pass-through workflow snapshot validator fake — see
 * [testDecompositionManifestValidator] for the rationale. Real workflow-state
 * schema loud-fail coverage lives in the infra-fs validator tests.
 */
internal val testWorkflowSnapshotValidator: WorkflowSnapshotValidator =
  object : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }

/** Pass-through install-plan wire validator fake — see above for rationale. */
internal val testInstallPlanWireValidator: InstallPlanWireValidator =
  object : InstallPlanWireValidator {
    override fun validate(plan: Map<String, Any?>) = Unit
  }

internal fun loadDecompositionManifest(path: Path) =
  loadDecompositionManifest(path, TestDecompositionManifestFileStore, testDecompositionManifestValidator)

internal fun writeIfDecomposed(request: DecompositionManifestWriteRequest): DecompositionManifestWriteResult? =
  DecompositionManifestWriter.writeIfDecomposed(
    request,
    testDecompositionManifestValidator,
    TestDecompositionManifestFileStore,
  )

internal fun writeFromWorkflowUpdate(
  repoRoot: Path,
  existingArtifactsJson: String,
  artifactsPatch: Map<String, Any?>?,
  runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
): DecompositionManifestWriteResult? = DecompositionManifestWriter.writeFromWorkflowUpdate(
  repoRoot = repoRoot,
  existingArtifactsJson = existingArtifactsJson,
  artifactsPatch = artifactsPatch,
  validator = testDecompositionManifestValidator,
  runtimeUpdate = runtimeUpdate,
  fileStore = TestDecompositionManifestFileStore,
)

internal fun writeProjectionFromWorkflowState(
  repoRoot: Path,
  artifactsJson: String,
): DecompositionManifestWriteResult? = DecompositionManifestWriter.writeProjectionFromWorkflowState(
  repoRoot = repoRoot,
  artifactsJson = artifactsJson,
  validator = testDecompositionManifestValidator,
  fileStore = TestDecompositionManifestFileStore,
)
