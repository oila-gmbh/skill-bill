package skillbill.ports.scaffold.manifest.model

import skillbill.scaffold.model.CodeReviewBaselineLayer
import java.nio.file.Path

/**
 * Request to overwrite a platform-pack manifest at [manifestPath] with [content]. The adapter
 * snapshots the existing file (if any) so the scaffold transaction can roll back.
 */
data class ScaffoldManifestWriteRequest(
  val manifestPath: Path,
  val content: String,
)

/**
 * Request to append a new code-review area declaration to an existing platform-pack manifest.
 */
data class ScaffoldManifestAppendCodeReviewAreaRequest(
  val manifestPath: Path,
  val area: String,
  val relativeContentPath: String,
  val areaFocus: String,
)

/**
 * Request to set or replace the `declared_quality_check_file:` entry in an existing manifest.
 */
data class ScaffoldManifestSetDeclaredQualityCheckRequest(
  val manifestPath: Path,
  val relativeContentPath: String,
)

/**
 * Request to register a governed add-on (pointer + addon-usage entries) in a manifest.
 */
data class ScaffoldManifestRegisterGovernedAddonRequest(
  val manifestPath: Path,
  val platform: String,
  val skillRelativeDirs: List<String>,
  val addonSlug: String,
)

/**
 * Read result mapping the requested manifest path to its current text content.
 */
data class ScaffoldManifestReadResult(
  val manifestPath: Path,
  val content: String,
)

/**
 * Request to render the canonical platform-pack manifest YAML text for a freshly scaffolded pack.
 * Adapters delegate to the pure-policy renderer in `runtime-domain`.
 */
data class ScaffoldManifestRenderPlatformPackRequest(
  val platform: String,
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String>,
  val declaredCodeReviewAreas: List<String>,
  val baselineContentPath: String,
  val declaredAreaFiles: Map<String, String>,
  val declaredQualityCheckFile: String?,
  val areaMetadata: Map<String, String>,
  val baselineLayers: List<CodeReviewBaselineLayer>,
)

/**
 * Snapshot captured before a manifest mutation so the scaffold rollback can restore it byte-for-byte.
 */
data class ScaffoldManifestSnapshot(
  val manifestPath: Path,
  val originalBytes: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ScaffoldManifestSnapshot) return false
    return manifestPath == other.manifestPath && originalBytes.contentEquals(other.originalBytes)
  }

  override fun hashCode(): Int = 31 * manifestPath.hashCode() + originalBytes.contentHashCode()
}
