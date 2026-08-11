package skillbill.ports.scaffold

import skillbill.scaffold.model.PlatformManifest

/**
 * Platform packs the user actually installed — the selection recorded at install time, not every
 * pack that ships. Both code review and the validate gate resolve from here, so neither can reach
 * a pack the user declined; consult this rather than discovering packs under the repository being
 * worked on, which belongs to that project and may not describe this runtime at all.
 */
fun interface InstalledPlatformPackCatalogPort {
  fun manifests(): List<PlatformManifest>

  companion object {
    val NONE = InstalledPlatformPackCatalogPort { emptyList() }
  }
}
