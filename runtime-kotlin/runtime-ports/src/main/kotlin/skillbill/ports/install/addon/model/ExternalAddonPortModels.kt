package skillbill.ports.install.addon.model

import skillbill.install.model.ExternalAddonSource
import java.nio.file.Path

data class ExternalAddonSourceConfigRequest(
  val userHome: Path,
  val environment: Map<String, String> = emptyMap(),
)

data class ExternalAddonSourceRegistrationRequest(
  val userHome: Path,
  val environment: Map<String, String> = emptyMap(),
  val source: ExternalAddonSource,
)

data class ExternalAddonSourceConfigResult(
  val sources: List<ExternalAddonSource> = emptyList(),
)

data class ExternalAddonOverlayRequest(
  val platformPacksRoot: Path,
  val sources: List<ExternalAddonSource>,
)

data class ExternalAddonOverlayResult(
  val appliedSources: List<AppliedExternalAddonSource> = emptyList(),
  val skippedSources: List<SkippedExternalAddonSource> = emptyList(),
  val touched: Boolean = false,
)

data class AppliedExternalAddonSource(
  val platform: String,
  val sourcePath: Path,
  val addons: List<String>,
)

data class SkippedExternalAddonSource(
  val platform: String,
  val sourcePath: Path,
  val reason: String,
)
