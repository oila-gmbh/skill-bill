package skillbill.ports.scaffold.source.model

import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

/**
 * Request to load a single platform pack from disk. Adapters resolve the canonical
 * `platform.yaml` parse seam under [packRoot].
 */
data class ScaffoldPlatformPackLoadRequest(
  val packRoot: Path,
)

/**
 * Result of loading a single platform pack from disk. Adapters resolve the canonical
 * `platform.yaml` parse seam and yield the typed manifest.
 */
data class ScaffoldPlatformPackLoadResult(
  val packRoot: Path,
  val manifest: PlatformManifest,
)
