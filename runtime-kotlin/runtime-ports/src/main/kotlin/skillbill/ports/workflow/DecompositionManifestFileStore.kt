package skillbill.ports.workflow

import skillbill.boundary.OpenBoundaryMap
import java.nio.file.Path

interface DecompositionManifestFileStore {
  fun readText(path: Path): String
  fun isRegularFile(path: Path): Boolean
  fun findDecompositionManifestFiles(repoRoot: Path): List<Path>
  fun writeTextAtomically(target: Path, content: String)

  /**
   * Serializes a schema-validated decomposition-manifest wire map to YAML
   * text. The raw map is the canonical wire-shape envelope at the
   * infra/codec serialization seam — it mirrors the
   * [skillbill.workflow.DecompositionManifestValidator] decode seam, so the
   * concrete `YAMLMapper` mechanics stay in the infra-fs adapter.
   */
  @OpenBoundaryMap("Decomposition manifest wire map at the YAML serialization seam")
  fun encodeManifestYaml(wireMap: Map<String, Any?>): String
}

object UnavailableDecompositionManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = unavailable()

  override fun isRegularFile(path: Path): Boolean = unavailable()

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = unavailable()

  override fun writeTextAtomically(target: Path, content: String): Unit = unavailable()

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = unavailable()

  private fun unavailable(): Nothing {
    error("Decomposition manifest file store is not configured for this runtime.")
  }
}
