package skillbill.ports.scaffold.manifest

import skillbill.ports.scaffold.manifest.model.ScaffoldManifestAppendCodeReviewAreaRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestReadResult
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestRegisterGovernedAddonRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestRenderPlatformPackRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestSetDeclaredQualityCheckRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestSnapshot
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestWriteRequest
import java.nio.file.Path

/**
 * Capability port for scaffold-time platform-pack manifest persistence. Implementations own
 * the YAML file IO (read, snapshot, write, restore). The pure-policy renderer in
 * `runtime-domain` produces the canonical YAML strings; this port persists them.
 *
 * IO ownership lives in `runtime-infra-fs`. Policy code depends only on this port.
 */
interface ScaffoldManifestPersistencePort {
  /** Reads the current text of a manifest (used for previewing manifest edits during dry runs). */
  fun read(manifestPath: Path): ScaffoldManifestReadResult

  /** Captures a snapshot of the manifest bytes for rollback. */
  fun snapshot(manifestPath: Path): ScaffoldManifestSnapshot

  /** Restores a previously-captured snapshot. */
  fun restore(snapshot: ScaffoldManifestSnapshot)

  /** Writes [request].content to [request].manifestPath. */
  fun write(request: ScaffoldManifestWriteRequest)

  /** Renders a freshly scaffolded platform-pack manifest as canonical YAML text. */
  fun renderPlatformPackManifest(request: ScaffoldManifestRenderPlatformPackRequest): String

  /** Appends a new code-review area entry to an existing manifest. */
  fun appendCodeReviewArea(request: ScaffoldManifestAppendCodeReviewAreaRequest)

  /** Sets the `declared_quality_check_file:` line in an existing manifest. */
  fun setDeclaredQualityCheckFile(request: ScaffoldManifestSetDeclaredQualityCheckRequest)

  /** Registers a governed add-on (pointer + addon-usage entries) in an existing manifest. */
  fun registerGovernedAddon(request: ScaffoldManifestRegisterGovernedAddonRequest)

  /**
   * Renders the projected text a governed-addon manifest registration would produce, without
   * touching disk. Used to populate dry-run manifest previews.
   */
  fun renderGovernedAddonRegistrationPreview(
    currentText: String,
    request: ScaffoldManifestRegisterGovernedAddonRequest,
  ): String
}
