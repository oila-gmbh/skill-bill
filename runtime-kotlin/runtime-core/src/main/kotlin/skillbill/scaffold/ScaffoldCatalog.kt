package skillbill.scaffold

import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

/**
 * Stable, public accessor for scaffold-time option lists used by desktop wizards and other UI
 * surfaces. Keeps the underlying constants `internal` to the scaffold package while exposing a
 * single read-only surface that wizards can render against.
 *
 * Wizard option lists must come from this catalog (or runtime metadata discovered via
 * [discoverPilotedPlatformPacks]). UI code must never duplicate these lists locally — every
 * payload produced by a wizard is validated against the same constants when the scaffolder runs.
 *
 * F-001: Every member delegates to the matching `internal` constant declared in
 * `ScaffoldSupport.kt` (`APPROVED_CODE_REVIEW_AREAS`, `PRE_SHELL_FAMILIES`, `SHELLED_FAMILIES`,
 * `PLATFORM_PACK_PRESETS`, `SCAFFOLD_PAYLOAD_VERSION`) so the runtime stays the single source of
 * truth. There must be no parallel literal copies on this object.
 */
object ScaffoldCatalog {
  /** Approved set of code-review specialist areas; payloads must restrict `area` to this set. */
  val approvedCodeReviewAreas: Set<String>
    get() = APPROVED_CODE_REVIEW_AREAS

  /** Families that have NOT yet been migrated to the shell+content contract. */
  val preShellFamilies: Set<String>
    get() = PRE_SHELL_FAMILIES

  /** Families that are piloted onto the shell+content contract and accept platform overrides. */
  val shelledFamilies: Set<String>
    get() = SHELLED_FAMILIES

  /** Built-in platform-pack presets (slug -> display name). */
  val platformPackPresets: Map<String, String>
    get() = PLATFORM_PACK_PRESETS

  /** Required `scaffold_payload_version` value for SCAFFOLD_PAYLOAD.md v1.0 contract. */
  val scaffoldPayloadVersion: String
    get() = SCAFFOLD_PAYLOAD_VERSION

  /**
   * Discover the set of piloted platform packs available under [packsRoot]. Delegates to the
   * scaffold-package manifest discovery so callers do not need to depend on internal helpers.
   */
  fun discoverPilotedPlatformPacks(packsRoot: Path): List<PlatformManifest> = discoverPlatformPackManifests(packsRoot)
}
