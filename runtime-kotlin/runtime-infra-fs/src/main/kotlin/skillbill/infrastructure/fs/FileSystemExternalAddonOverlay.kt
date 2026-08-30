package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.model.AppliedExternalAddonSource
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.install.addon.model.ExternalAddonOverlayResult
import skillbill.ports.install.addon.model.SkippedExternalAddonSource
import skillbill.scaffold.platformpack.loadPlatformManifest
import java.nio.file.Files

internal const val ADDONS_DIR = "addons"
internal const val MANIFEST_FILE = "platform.yaml"
internal const val SOURCE_MANIFEST_FILE = "addon-manifest.yaml"
internal const val MANIFEST_TEMP_SUFFIX = ".platform.yaml.tmp"
internal val POINTER_NAME_PATTERN = Regex("^[^/\\\\]+\\.md$")
internal val ADDON_SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*\$")
internal val POINTER_ENTRY_KEYS = setOf("name", "target")
internal val ADDON_ENTRY_KEYS = setOf("slug", "entrypoint", "companion_pointers", "activation", "specialist_areas")

@Inject
class FileSystemExternalAddonOverlay : ExternalAddonOverlayPort {

  override fun applyOverlay(request: ExternalAddonOverlayRequest): ExternalAddonOverlayResult {
    if (request.sources.isEmpty()) {
      return ExternalAddonOverlayResult(touched = false)
    }

    val platformPacksRoot = request.platformPacksRoot.toAbsolutePath().normalize()
    val skipped = mutableListOf<SkippedExternalAddonSource>()
    val plans = mutableListOf<SourcePlan>()
    val collisionIndex = CollisionIndex.empty()

    for (source in request.sources) {
      val packRoot = platformPacksRoot.resolve(source.platform)
      val manifestPath = packRoot.resolve(MANIFEST_FILE)
      if (!Files.isRegularFile(manifestPath)) {
        skipped += SkippedExternalAddonSource(
          platform = source.platform,
          sourcePath = source.path,
          reason = "platform pack '${source.platform}' is not installed; skipping external addon source.",
        )
        continue
      }
      val installed = loadPlatformManifest(packRoot)
      collisionIndex.mergeInstalled(installed.pointers, installed.addonUsage)
      val plan = validateAndPlan(source, installed, collisionIndex)
      plans += plan
    }

    plans.forEach(::applyPlan)

    val applied = plans.map { plan ->
      AppliedExternalAddonSource(
        platform = plan.platform,
        sourcePath = plan.sourcePath,
        addons = plan.copiedFiles.values.map { it.fileName.toString() }.sorted(),
      )
    }
    return ExternalAddonOverlayResult(
      appliedSources = applied,
      skippedSources = skipped,
      touched = plans.isNotEmpty(),
    )
  }
}
