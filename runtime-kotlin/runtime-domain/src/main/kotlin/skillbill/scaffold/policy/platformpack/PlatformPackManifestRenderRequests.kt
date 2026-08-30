package skillbill.scaffold.policy.platformpack

import skillbill.scaffold.model.CodeReviewBaselineLayer
import java.nio.file.Path

data class PlatformPackManifestRenderRequest(
  val platform: String,
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String> = emptyList(),
  val declaredCodeReviewAreas: List<String> = emptyList(),
  val baselineContentPath: String,
  val declaredAreaFiles: Map<String, String> = emptyMap(),
  val declaredQualityCheckFile: String? = null,
  val areaMetadata: Map<String, String> = emptyMap(),
  val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  val notes: String? = null,
)

data class PlatformPackManifestContentRenderRequest(
  val platform: String,
  val displayName: String,
  val routingSignals: List<String>,
  val tieBreakers: List<String>,
  val specialistAreas: List<String>,
  val specialistAreaMetadata: Map<String, String>,
  val baselineLayers: List<CodeReviewBaselineLayer>,
  val packRoot: Path,
  val baselineSkillPath: Path,
  val qualityCheckSkillPath: Path,
  val specialistSkillPaths: Map<String, Path>,
)
