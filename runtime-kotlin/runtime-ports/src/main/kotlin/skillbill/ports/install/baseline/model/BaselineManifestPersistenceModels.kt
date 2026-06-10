package skillbill.ports.install.baseline.model

import skillbill.install.model.BaselineManifest
import java.nio.file.Path

/**
 * SKILL-76 Subtask 2: typed request/result models for the baseline manifest
 * persistence port. The manifest lives at `<home>/.skill-bill/baseline-manifest.json`;
 * callers pass the install home and the adapter resolves the canonical path, mirroring
 * [skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest].
 */
data class ReadBaselineManifestRequest(
  val installHome: Path,
)

data class ReadBaselineManifestResult(
  val manifest: BaselineManifest,
  val existed: Boolean,
)

data class WriteBaselineManifestRequest(
  val installHome: Path,
  val manifest: BaselineManifest,
)

data class WriteBaselineManifestResult(
  val path: Path,
)
