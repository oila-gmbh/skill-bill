
@file:Suppress("ThrowsCount", "TooManyFunctions")

package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import org.yaml.snakeyaml.Yaml
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
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.declaredSkillRelativeDirs
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.parseAddonUsage
import skillbill.scaffold.platformpack.parsePointers
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val ADDONS_DIR = "addons"
private const val MANIFEST_FILE = "platform.yaml"
private const val SOURCE_MANIFEST_FILE = "addon-manifest.yaml"
private const val MANIFEST_TEMP_SUFFIX = ".platform.yaml.tmp"
private val POINTER_NAME_PATTERN = Regex("^[^/\\\\]+\\.md$")
private val ADDON_SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*\$")
private val POINTER_ENTRY_KEYS = setOf("name", "target")
private val ADDON_ENTRY_KEYS = setOf("slug", "entrypoint", "companion_pointers", "activation", "specialist_areas")

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

    plans.forEach { applyPlan(it) }

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

  private fun validateAndPlan(
    source: ExternalAddonSource,
    installed: skillbill.scaffold.model.PlatformManifest,
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
        manifest = rewritten,
        slug = slug,
        packRoot = installed.packRoot,
        pointers = fragmentPointers,
        declaredSkillDirs = installed.declaredSkillRelativeDirs(),
        declaredAreas = installed.declaredCodeReviewAreas.toSet(),
        strictReviewRouting = installed.laneConditions.isNotEmpty(),
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

  private fun collectPointersToAppend(
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

  private fun collectAddonsToAppend(
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

  private fun readSourceManifest(sourcePath: Path, slug: String): Map<String, Any?> {
    val manifestPath = sourcePath.resolve(SOURCE_MANIFEST_FILE)
    if (!Files.isRegularFile(manifestPath)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': expected '$manifestPath' but it is missing.",
      )
    }
    val raw = try {
      Yaml().load<Any?>(Files.readString(manifestPath))
    } catch (error: org.yaml.snakeyaml.error.YAMLException) {
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

  private fun rewriteFragmentTargets(fragment: Map<String, Any?>, slug: String): Map<String, Any?> {
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

  private fun rewritePointerEntry(
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

  private fun validateFragmentFields(fragment: Map<String, Any?>, slug: String) {
    validatePointerEntries(fragment, slug)
    validateAddonUsageEntries(fragment, slug)
  }

  private fun validatePointerEntries(fragment: Map<String, Any?>, slug: String) {
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

  private fun validatePointerEntry(slug: String, dir: String, index: Int, entry: Any?) {
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

  private fun validateAddonUsageEntries(fragment: Map<String, Any?>, slug: String) {
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

  private fun validateAddonUsageEntry(slug: String, dir: String, index: Int, entry: Any?) {
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

  private fun fragmentFieldMessage(slug: String, field: String, extra: Set<String>, allowed: String): String =
    "External addon source for platform '$slug': $field has unexpected keys ${extra.sorted()} " +
      "(only $allowed are allowed)."

  private fun isValidPointerName(name: String): Boolean = POINTER_NAME_PATTERN.matches(name) && !name.contains("..")

  private fun requireFlatAddonTarget(slug: String, target: String) {
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

  private fun <T> wrapParserErrors(slug: String, block: () -> T): T = try {
    block()
  } catch (error: InvalidManifestSchemaError) {
    throw ExternalAddonOverlayError(
      "External addon source for platform '$slug': fragment validation failed: ${error.message}",
      error,
    )
  }

  private fun verifySourceFile(sourcePath: Path, slug: String, filename: String) {
    val file = sourcePath.resolve(filename)
    if (!Files.isRegularFile(file)) {
      throw ExternalAddonOverlayError(
        "External addon source for platform '$slug': referenced addon file '$file' is missing.",
      )
    }
  }

  private fun applyPlan(plan: SourcePlan) {
    val manifestChanged = plan.pointersToAppend.isNotEmpty() || plan.addonsToAppend.isNotEmpty()
    if (manifestChanged) {
      mergeIntoManifest(plan)
    }
    if (plan.copiedFiles.isEmpty()) return
    val addonsDir = plan.packRoot.resolve(ADDONS_DIR)
    Files.createDirectories(addonsDir)
    plan.copiedFiles.forEach { (filename, sourceFile) ->
      Files.copy(sourceFile, addonsDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun mergeIntoManifest(plan: SourcePlan) {
    val manifestPath = plan.installedManifestPath
    val root = readRawManifest(manifestPath)
    if (plan.pointersToAppend.isNotEmpty()) {
      appendPointers(plan, root)
    }
    if (plan.addonsToAppend.isNotEmpty()) {
      appendAddonUsage(plan, root)
    }
    val tempFile = manifestPath.resolveSibling(MANIFEST_TEMP_SUFFIX)
    Files.writeString(tempFile, Yaml().dump(root))
    try {
      atomicMove(tempFile, manifestPath)
    } catch (error: java.io.IOException) {
      Files.deleteIfExists(tempFile)
      throw error
    }
  }

  private fun appendPointers(plan: SourcePlan, root: MutableMap<String, Any?>) {
    val pointersRoot = root
      .getOrPut("pointers") { linkedMapOf<String, Any?>() }
      .asMutableMap(plan.platform, "pointers")
    plan.pointersToAppend.forEach { (dir, entries) ->
      val list = pointersRoot
        .getOrPut(dir) { mutableListOf<Any?>() }
        .asMutableList(plan.platform, "pointers[$dir]")
      entries.forEach { spec -> list.add(linkedMapOf("name" to spec.name, "target" to spec.target)) }
    }
  }

  private fun appendAddonUsage(plan: SourcePlan, root: MutableMap<String, Any?>) {
    val addonUsageRoot = root
      .getOrPut("addon_usage") { linkedMapOf<String, Any?>() }
      .asMutableMap(plan.platform, "addon_usage")
    plan.addonsToAppend.forEach { (dir, entries) ->
      val list = addonUsageRoot
        .getOrPut(dir) { mutableListOf<Any?>() }
        .asMutableList(plan.platform, "addon_usage[$dir]")
      entries.forEach { selection ->
        val entry = linkedMapOf<String, Any?>("slug" to selection.slug, "entrypoint" to selection.entrypoint)
        if (selection.companionPointers.isNotEmpty()) {
          entry["companion_pointers"] = selection.companionPointers.toMutableList()
        }
        list.add(entry)
      }
    }
  }

  private fun atomicMove(source: Path, target: Path) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun Any?.asMutableMap(slug: String, field: String): MutableMap<String, Any?> = when (this) {
    is MutableMap<*, *> -> {
      @Suppress("UNCHECKED_CAST")
      try {
        this as MutableMap<String, Any?>
      } catch (error: ClassCastException) {
        throw manifestStructureError(slug, field, error)
      }
    }
    else -> throw manifestStructureError(slug, field, expected = "a mapping", actual = this)
  }

  private fun Any?.asMutableList(slug: String, field: String): MutableList<Any?> = when (this) {
    is MutableList<*> -> {
      @Suppress("UNCHECKED_CAST")
      try {
        this as MutableList<Any?>
      } catch (error: ClassCastException) {
        throw manifestStructureError(slug, field, error)
      }
    }
    else -> throw manifestStructureError(slug, field, expected = "a list", actual = this)
  }

  private fun manifestStructureError(
    slug: String,
    field: String,
    error: ClassCastException,
  ): ExternalAddonOverlayError {
    val message = "Installed platform.yaml for '$slug' has unexpected structure in '$field': ${error.message}"
    return ExternalAddonOverlayError(message, error)
  }

  private fun manifestStructureError(
    slug: String,
    field: String,
    expected: String,
    actual: Any?,
  ): ExternalAddonOverlayError {
    val found = actual?.javaClass?.simpleName ?: "null"
    val message = "Installed platform.yaml for '$slug' has unexpected structure in '$field': " +
      "expected $expected but found $found."
    return ExternalAddonOverlayError(message)
  }

  private fun readRawManifest(manifestPath: Path): MutableMap<String, Any?> {
    val raw = Yaml().load<Any?>(Files.readString(manifestPath)) as? Map<*, *>
      ?: throw ExternalAddonOverlayError("Installed platform manifest '$manifestPath' must be a YAML mapping.")
    val root = linkedMapOf<String, Any?>()
    raw.forEach { (k, v) ->
      root[k as String] = when (v) {
        is Map<*, *> -> linkedMapOfFrom(v)
        else -> v
      }
    }
    return root
  }

  private fun linkedMapOfFrom(map: Map<*, *>): MutableMap<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    map.forEach { (k, v) ->
      out[k as String] = when (v) {
        is Map<*, *> -> linkedMapOfFrom(v)
        is List<*> -> v.mapTo(mutableListOf()) { item ->
          when (item) {
            is Map<*, *> -> linkedMapOfFrom(item)
            else -> item
          }
        }
        else -> v
      }
    }
    return out
  }

  private fun collisionMessage(slug: String, dir: String, name: String, existing: String, incoming: String): String =
    "External addon overlay for platform '$slug': pointer '$name' under '$dir' collides with an existing " +
      "pack-owned target '$existing' (external source declares '$incoming')."

  private fun targetCollisionMessage(
    slug: String,
    pointer: PointerSpec,
    outcome: PointerCollisionOutcome.TargetCollision,
  ): String = "External addon overlay for platform '$slug': pointer '${pointer.name}' under " +
    "'${pointer.skillRelativeDir}' writes target file '${pointer.target}' that collides with the " +
    "${outcome.origin} pointer '${outcome.existingName}' (silent overwrite refused)."

  private fun addonCollisionMessage(
    slug: String,
    dir: String,
    addonSlug: String,
    existing: GovernedAddonSelection,
    incoming: GovernedAddonSelection,
  ): String = "External addon overlay for platform '$slug': add-on slug '$addonSlug' under '$dir' collides with an " +
    "existing entry (existing entrypoint '${existing.entrypoint}', incoming entrypoint '${incoming.entrypoint}')."
}

private typealias DirName = Pair<String, String>
private typealias DirTarget = Pair<String, String>
private typealias DirSlug = Pair<String, String>

private data class ExternalName(val platform: String, val dir: String, val name: String)
private data class ExternalTarget(val platform: String, val dir: String, val basename: String)
private data class ExternalSlug(val platform: String, val dir: String, val slug: String)

private fun PointerSpec.dirName(): DirName = skillRelativeDir to name

private fun basename(target: String): String = target.substringAfterLast('/').substringAfterLast('\\')

private sealed interface PointerCollisionOutcome {
  data object AlreadyPresent : PointerCollisionOutcome
  data class NameCollision(val existingTarget: String) : PointerCollisionOutcome
  data class TargetCollision(val existingName: String, val origin: String) : PointerCollisionOutcome
  data object New : PointerCollisionOutcome
}

private sealed interface AddonCollisionOutcome {
  data object AlreadyPresent : AddonCollisionOutcome
  data class Collision(val existing: GovernedAddonSelection) : AddonCollisionOutcome
  data object New : AddonCollisionOutcome
}

private class CollisionIndex {
  private val installedByName = mutableMapOf<DirName, String>()
  private val installedByTarget = mutableMapOf<DirTarget, String>()
  private val externalByName = mutableMapOf<ExternalName, String>()
  private val externalByTarget = mutableMapOf<ExternalTarget, String>()
  private val installedAddons = mutableMapOf<DirSlug, GovernedAddonSelection>()
  private val externalAddons = mutableMapOf<ExternalSlug, GovernedAddonSelection>()

  fun mergeInstalled(pointers: List<PointerSpec>, addonUsage: List<GovernedAddonUsage> = emptyList()) {
    installedByName.clear()
    installedByTarget.clear()
    installedAddons.clear()
    pointers.forEach { pointer ->
      installedByName[pointer.skillRelativeDir to pointer.name] = pointer.target
      installedByTarget[pointer.skillRelativeDir to basename(pointer.target)] = pointer.name
    }
    addonUsage.forEach { usage ->
      usage.addons.forEach { selection ->
        installedAddons[usage.skillRelativeDir to selection.slug] = selection
      }
    }
  }

  fun recordExternalPointer(platform: String, nameKey: DirName, targetKey: DirTarget, pointer: PointerSpec) {
    externalByName[ExternalName(platform, nameKey.first, nameKey.second)] = pointer.target
    externalByTarget[ExternalTarget(platform, targetKey.first, targetKey.second)] = pointer.name
  }

  fun classifyPointer(
    platform: String,
    pointer: PointerSpec,
    nameKey: DirName,
    targetKey: DirTarget,
  ): PointerCollisionOutcome {
    val nameOwner = installedByName[nameKey] ?: externalByName[ExternalName(platform, nameKey.first, nameKey.second)]
    if (nameOwner != null) {
      return if (nameOwner == pointer.target) {
        PointerCollisionOutcome.AlreadyPresent
      } else {
        PointerCollisionOutcome.NameCollision(nameOwner)
      }
    }
    val targetOwner = installedByTarget[targetKey]
    val externalTargetOwner = externalByTarget[ExternalTarget(platform, targetKey.first, targetKey.second)]
    return when {
      targetOwner != null -> PointerCollisionOutcome.TargetCollision(targetOwner, "pack-owned")
      externalTargetOwner != null -> PointerCollisionOutcome.TargetCollision(externalTargetOwner, "external")
      else -> PointerCollisionOutcome.New
    }
  }

  fun recordExternalAddon(platform: String, dir: String, selection: GovernedAddonSelection) {
    externalAddons[ExternalSlug(platform, dir, selection.slug)] = selection
  }

  fun classifyAddon(platform: String, dir: String, selection: GovernedAddonSelection): AddonCollisionOutcome {
    val installed = installedAddons[dir to selection.slug]
    if (installed != null) {
      return resolveAddonOutcome(installed, selection)
    }
    val external = externalAddons[ExternalSlug(platform, dir, selection.slug)]
    if (external != null) {
      return resolveAddonOutcome(external, selection)
    }
    return AddonCollisionOutcome.New
  }

  private fun resolveAddonOutcome(
    existing: GovernedAddonSelection,
    incoming: GovernedAddonSelection,
  ): AddonCollisionOutcome =
    if (existing == incoming) AddonCollisionOutcome.AlreadyPresent else AddonCollisionOutcome.Collision(existing)

  companion object {
    fun empty(): CollisionIndex = CollisionIndex()
  }
}

private data class SourcePlan(
  val platform: String,
  val sourcePath: Path,
  val installedManifestPath: Path,
  val packRoot: Path,
  val pointersToAppend: Map<String, List<PointerSpec>>,
  val addonsToAppend: Map<String, List<GovernedAddonSelection>>,
  val copiedFiles: Map<String, Path>,
)
