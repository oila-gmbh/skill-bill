
@file:Suppress("ThrowsCount", "TooManyFunctions")

package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import skillbill.error.ExternalAddonOverlayError
import skillbill.error.InvalidManifestSchemaError
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.model.AppliedExternalAddonSource
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.install.addon.model.ExternalAddonOverlayResult
import skillbill.ports.install.addon.model.SkippedExternalAddonSource
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.AddonUsageManifestContext
import skillbill.scaffold.platformpack.declaredSkillRelativeDirs
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.parseAddonUsage
import skillbill.scaffold.platformpack.parsePointers
import java.nio.file.Files
import java.nio.file.Path

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

  internal fun validateAndPlan(
    source: ExternalAddonSource,
    installed: PlatformManifest,
    collisionIndex: CollisionIndex,
  ): SourcePlan {
    val slug = source.platform
    val fragment = readSourceManifest(source.path, slug)
    val rewritten = rewriteFragmentTargets(fragment, slug)
    validateFragmentFields(rewritten, slug)
    val fragmentPointers = wrapParserErrors(slug) { parsePointers(rewritten, slug) }
    fragmentPointers.forEach { pointer -> requireFlatAddonTarget(slug, pointer.target) }
    val fragmentAddonUsage = wrapParserErrors(slug) {
      parseAddonUsage(
        rewritten,
        AddonUsageManifestContext(
          slug = slug,
          packRoot = installed.packRoot,
          pointers = fragmentPointers,
          declaredSkillDirs = installed.declaredSkillRelativeDirs(),
          declaredAreas = installed.declaredCodeReviewAreas.toSet(),
          strictReviewRouting = installed.laneConditions.isNotEmpty(),
        ),
      )
    }

    val filesToCopy = linkedMapOf<String, Path>()
    fragmentPointers.forEach { pointer ->
      val filename = pointer.target.substringAfterLast('/')
      verifySourceFile(source.path, slug, filename)
      filesToCopy[filename] = source.path.resolve(filename)
    }

    val pointersToAppend = collectPointersToAppend(slug, fragmentPointers, collisionIndex)
    val addonsToAppend = collectAddonsToAppend(slug, fragmentAddonUsage, collisionIndex)

    return SourcePlan(
      platform = slug,
      sourcePath = source.path,
      installedManifestPath = installed.packRoot.resolve(MANIFEST_FILE),
      packRoot = installed.packRoot,
      pointersToAppend = pointersToAppend,
      addonsToAppend = addonsToAppend,
      copiedFiles = filesToCopy,
    )
  }

  internal fun collectPointersToAppend(
    slug: String,
    pointers: List<PointerSpec>,
    collisions: CollisionIndex,
  ): MutableMap<String, MutableList<PointerSpec>> {
    val result = mutableMapOf<String, MutableList<PointerSpec>>()
    for (pointer in pointers) {
      val nameKey = pointer.dirName()
      val targetKey = pointer.skillRelativeDir to basename(pointer.target)
      when (val outcome = collisions.classifyPointer(slug, pointer, nameKey, targetKey)) {
        PointerCollisionOutcome.AlreadyPresent -> Unit
        is PointerCollisionOutcome.NameCollision -> throw ExternalAddonOverlayError(
          collisionMessage(slug, pointer.skillRelativeDir, pointer.name, outcome.existingTarget, pointer.target),
        )
        is PointerCollisionOutcome.TargetCollision -> throw ExternalAddonOverlayError(
          targetCollisionMessage(slug, pointer, outcome),
        )
        PointerCollisionOutcome.New -> {
          collisions.recordExternalPointer(slug, nameKey, targetKey, pointer)
          result.getOrPut(pointer.skillRelativeDir) { mutableListOf() } += pointer
        }
      }
    }
    return result
  }

  internal fun collectAddonsToAppend(
    slug: String,
    addonUsage: List<GovernedAddonUsage>,
    collisions: CollisionIndex,
  ): MutableMap<String, MutableList<GovernedAddonSelection>> {
    val result = mutableMapOf<String, MutableList<GovernedAddonSelection>>()
    for (usage in addonUsage) {
      val dir = usage.skillRelativeDir
      for (selection in usage.addons) {
        when (val outcome = collisions.classifyAddon(slug, dir, selection)) {
          AddonCollisionOutcome.AlreadyPresent -> Unit
          is AddonCollisionOutcome.Collision -> throw ExternalAddonOverlayError(
            addonCollisionMessage(slug, dir, selection.slug, outcome.existing, selection),
          )
          AddonCollisionOutcome.New -> {
            collisions.recordExternalAddon(slug, dir, selection)
            result.getOrPut(dir) { mutableListOf() } += selection
          }
        }
      }
    }
    return result
  }

  internal fun readSourceManifest(sourcePath: Path, slug: String): Map<String, Any?> {
    val manifestPath = sourcePath.resolve(SOURCE_MANIFEST_FILE)
    if (!Files.isRegularFile(manifestPath)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': expected '$manifestPath' but it is missing.",
      )
    }
    val raw = try {
      Yaml().load<Any?>(Files.readString(manifestPath))
    } catch (error: YAMLException) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
        error,
      )
    }
    val rawMap = raw as? Map<*, *>
      ?: throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': manifest '$manifestPath' must be a YAML mapping.",
      )
    val typed = linkedMapOf<String, Any?>()
    rawMap.forEach { (k, v) ->
      val key = k as? String
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': manifest keys must be strings.",
        )
      typed[key] = v
    }
    return typed
  }

  internal fun rewriteFragmentTargets(fragment: Map<String, Any?>, slug: String): Map<String, Any?> {
    val pointers = (fragment["pointers"] as? Map<*, *>) ?: return fragment
    val rewritten = linkedMapOf<String, Any?>()
    fragment.forEach { (k, v) -> rewritten[k] = v }
    val rewrittenPointers = linkedMapOf<String, Any?>()
    val canonicalPrefix = "platform-packs/$slug/$ADDONS_DIR/"
    pointers.forEach { (dirKey, entriesRaw) ->
      val dir = dirKey as? String
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': pointers keys must be strings.",
        )
      val entries = (entriesRaw as? List<*>)
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': pointers[$dir] must be a list.",
        )
      val rewrittenEntries = entries.map { entry -> rewritePointerEntry(slug, dir, entry, canonicalPrefix) }
      rewrittenPointers[dir] = rewrittenEntries
    }
    rewritten["pointers"] = rewrittenPointers
    return rewritten
  }

  internal fun rewritePointerEntry(
    slug: String,
    dir: String,
    entry: Any?,
    canonicalPrefix: String,
  ): MutableMap<String, Any?> {
    val rawMap = entry as? Map<*, *>
      ?: throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': pointers[$dir] entries must be mappings.",
      )
    val map = linkedMapOf<String, Any?>()
    rawMap.forEach { (k, v) -> map[k as String] = v }
    val target = map["target"] as? String
      ?: throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': pointers[$dir] entry is missing string field 'target'.",
      )
    if (!target.startsWith(canonicalPrefix)) {
      val filename = Path.of(target).fileName.toString()
      map["target"] = canonicalPrefix + filename
    }
    return map
  }

  internal fun validateFragmentFields(fragment: Map<String, Any?>, slug: String) {
    validatePointerEntries(fragment, slug)
    validateAddonUsageEntries(fragment, slug)
  }

  internal fun validatePointerEntries(fragment: Map<String, Any?>, slug: String) {
    val pointers = fragment["pointers"] as? Map<*, *> ?: return
    pointers.forEach { (dirKey, entriesRaw) ->
      val dir = dirKey as? String
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': pointers keys must be strings.",
        )
      val entries = (entriesRaw as? List<*>)
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': pointers[$dir] must be a list.",
        )
      entries.forEachIndexed { index, entry ->
        validatePointerEntry(slug, dir, index, entry)
      }
    }
  }

  internal fun validatePointerEntry(slug: String, dir: String, index: Int, entry: Any?) {
    val entryMap = entry as? Map<*, *>
      ?: throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': pointers[$dir][$index] must be a mapping.",
      )
    val keys = entryMap.keys.mapNotNull { it as? String }.toSet()
    val extra = keys - POINTER_ENTRY_KEYS
    if (extra.isNotEmpty()) {
      throw ExternalAddonOverlayError(
        fragmentFieldMessage(slug, "pointers[$dir][$index]", extra, "name and target"),
      )
    }
    val name = entryMap["name"] as? String
    if (name != null && !isValidPointerName(name)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': pointers[$dir][$index].name '$name' " +
          "must be a bare markdown filename (no separators, no '..' segments, ending in '.md').",
      )
    }
  }

  internal fun validateAddonUsageEntries(fragment: Map<String, Any?>, slug: String) {
    val addonUsage = fragment["addon_usage"] as? Map<*, *> ?: return
    addonUsage.forEach { (dirKey, entriesRaw) ->
      val dir = dirKey as? String
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': addon_usage keys must be strings.",
        )
      val entries = (entriesRaw as? List<*>)
        ?: throw ExternalAddonOverlayError(
          "External addon source for platform '$slug': addon_usage[$dir] must be a list.",
        )
      entries.forEachIndexed { index, entry ->
        validateAddonUsageEntry(slug, dir, index, entry)
      }
    }
  }

  internal fun validateAddonUsageEntry(slug: String, dir: String, index: Int, entry: Any?) {
    val entryMap = entry as? Map<*, *>
      ?: throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': addon_usage[$dir][$index] must be a mapping.",
      )
    val keys = entryMap.keys.mapNotNull { it as? String }.toSet()
    val extra = keys - ADDON_ENTRY_KEYS
    if (extra.isNotEmpty()) {
      throw ExternalAddonOverlayError(
        fragmentFieldMessage(
          slug,
          "addon_usage[$dir][$index]",
          extra,
          "slug, entrypoint, companion_pointers, activation, and specialist_areas",
        ),
      )
    }
    val addonSlug = entryMap["slug"] as? String
    if (addonSlug != null && !ADDON_SLUG_PATTERN.matches(addonSlug)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': addon_usage[$dir][$index].slug '$addonSlug' " +
          "must match '${ADDON_SLUG_PATTERN.pattern}'.",
      )
    }
  }

  internal fun fragmentFieldMessage(slug: String, field: String, extra: Set<String>, allowed: String): String =
    "External addon source for platform '$slug': $field has unexpected keys ${extra.sorted()} " +
      "(only $allowed are allowed)."

  internal fun isValidPointerName(name: String): Boolean = POINTER_NAME_PATTERN.matches(name) && !name.contains("..")

  internal fun requireFlatAddonTarget(slug: String, target: String) {
    val expectedPrefix = "platform-packs/$slug/$ADDONS_DIR/"
    if (!target.startsWith(expectedPrefix)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': pointer target '$target' must start with '$expectedPrefix'.",
      )
    }
    val remainder = target.removePrefix(expectedPrefix)
    if (remainder.contains('/') || remainder.contains('\\') || remainder.isEmpty()) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': pointer target '$target' must be a flat file directly " +
          "under '$expectedPrefix' (no subdirectories).",
      )
    }
  }

  internal fun <T> wrapParserErrors(slug: String, block: () -> T): T = try {
    block()
  } catch (error: InvalidManifestSchemaError) {
    throw ExternalAddonOverlayError(
      "External addon source for platform '$slug': fragment validation failed: ${error.message}",
      error,
    )
  }

  internal fun verifySourceFile(sourcePath: Path, slug: String, filename: String) {
    val file = sourcePath.resolve(filename)
    if (!Files.isRegularFile(file)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': referenced addon file '$file' is missing.",
      )
    }
  }

  internal fun collisionMessage(slug: String, dir: String, name: String, existing: String, incoming: String): String =
    "External addon overlay for platform '$slug': pointer '$name' under '$dir' collides with an existing " +
      "pack-owned target '$existing' (external source declares '$incoming')."

  internal fun targetCollisionMessage(
    slug: String,
    pointer: PointerSpec,
    outcome: PointerCollisionOutcome.TargetCollision,
  ): String = "External addon overlay for platform '$slug': pointer '${pointer.name}' under " +
    "'${pointer.skillRelativeDir}' writes target file '${pointer.target}' that collides with the " +
    "${outcome.origin} pointer '${outcome.existingName}' (silent overwrite refused)."

  internal fun addonCollisionMessage(
    slug: String,
    dir: String,
    addonSlug: String,
    existing: GovernedAddonSelection,
    incoming: GovernedAddonSelection,
  ): String = "External addon overlay for platform '$slug': add-on slug '$addonSlug' under '$dir' collides with an " +
    "existing entry (existing entrypoint '${existing.entrypoint}', incoming entrypoint '${incoming.entrypoint}')."
}
