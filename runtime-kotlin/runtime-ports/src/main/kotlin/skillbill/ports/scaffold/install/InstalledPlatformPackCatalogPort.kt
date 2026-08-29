package skillbill.ports.scaffold.install

import skillbill.scaffold.model.PlatformManifest

fun interface InstalledPlatformPackCatalogPort {
  fun manifests(): List<PlatformManifest>

  companion object {
    val NONE = InstalledPlatformPackCatalogPort { emptyList() }
  }
}
