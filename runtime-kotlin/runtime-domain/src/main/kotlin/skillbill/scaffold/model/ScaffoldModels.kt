package skillbill.scaffold.model

import java.nio.file.Path

data class RoutingSignals(
  val strong: List<String>,
  val tieBreakers: List<String>,
)

data class DeclaredFiles(
  val baseline: Path,
  val areas: Map<String, Path>,
)

data class PlatformManifest(
  val slug: String,
  val packRoot: Path,
  val contractVersion: String,
  val routingSignals: RoutingSignals,
  val declaredCodeReviewAreas: List<String>,
  val declaredFiles: DeclaredFiles,
  val areaMetadata: Map<String, String>,
  val displayName: String? = null,
  val notes: String? = null,
  val declaredQualityCheckFile: Path? = null,
) {
  val routedSkillName: String = "bill-$slug-code-review"
}

data class GovernedAddonFile(
  val packSlug: String,
  val addonPath: Path,
) {
  val addonSlug: String = addonPath.fileName.toString().removeSuffix(".md")
}

data class ScaffoldResult(
  val kind: String,
  val skillName: String,
  val skillPath: Path,
  val createdFiles: List<Path> = emptyList(),
  val manifestEdits: List<Path> = emptyList(),
  val symlinks: List<Path> = emptyList(),
  val installTargets: List<Path> = emptyList(),
  val notes: List<String> = emptyList(),
)
