package skillbill.infrastructure.fs

import org.yaml.snakeyaml.Yaml
import skillbill.error.ExternalAddonOverlayError
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PointerSpec
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.io.IOException

internal fun applyPlan(plan: SourcePlan) {
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

internal fun mergeIntoManifest(plan: SourcePlan) {
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
  } catch (error: IOException) {
    Files.deleteIfExists(tempFile)
    throw error
  }
}

internal fun appendPointers(plan: SourcePlan, root: MutableMap<String, Any?>) {
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

internal fun appendAddonUsage(plan: SourcePlan, root: MutableMap<String, Any?>) {
  val addonUsageRoot = root
    .getOrPut("addon_usage") { linkedMapOf<String, Any?>() }
    .asMutableMap(plan.platform, "addon_usage")
  plan.addonsToAppend.forEach { (dir, entries) ->
    val list = addonUsageRoot
      .getOrPut(dir) { mutableListOf<Any?>() }
      .asMutableList(plan.platform, "addon_usage[$dir]")
    entries.forEach { selection -> list.add(addonUsageEntry(selection)) }
  }
}

internal fun addonUsageEntry(selection: GovernedAddonSelection): MutableMap<String, Any?> {
  val entry = linkedMapOf<String, Any?>("slug" to selection.slug, "entrypoint" to selection.entrypoint)
  if (selection.companionPointers.isNotEmpty()) {
    entry["companion_pointers"] = selection.companionPointers.toMutableList()
  }
  selection.activation?.let { activation -> entry["activation"] = activationEntry(activation) }
  if (selection.specialistAreas.isNotEmpty()) {
    entry["specialist_areas"] = selection.specialistAreas.toMutableList()
  }
  return entry
}

internal fun activationEntry(activation: GovernedAddonActivation): MutableMap<String, Any?> {
  val entry = linkedMapOf<String, Any?>()
  fun put(field: String, values: List<String>) {
    if (values.isNotEmpty()) entry[field] = values.toMutableList()
  }
  put("any_path", activation.anyPath)
  put("any_content", activation.anyContent)
  put("all_content", activation.allContent)
  if (activation.anyOfAllContent.isNotEmpty()) {
    entry["any_of_all_content"] = activation.anyOfAllContent.map { group -> group.toMutableList() }.toMutableList()
  }
  put("exclude_path", activation.excludePath)
  put("exclude_content", activation.excludeContent)
  return entry
}

internal fun atomicMove(source: Path, target: Path) {
  try {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
  }
}

internal fun Any?.asMutableMap(slug: String, field: String): MutableMap<String, Any?> = when (this) {
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

internal fun Any?.asMutableList(slug: String, field: String): MutableList<Any?> = when (this) {
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

internal fun manifestStructureError(
  slug: String,
  field: String,
  error: ClassCastException,
): ExternalAddonOverlayError {
  val message = "Installed platform.yaml for '$slug' has unexpected structure in '$field': ${error.message}"
  return ExternalAddonOverlayError(message, error)
}

internal fun manifestStructureError(
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

internal fun readRawManifest(manifestPath: Path): MutableMap<String, Any?> {
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

internal fun linkedMapOfFrom(map: Map<*, *>): MutableMap<String, Any?> {
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
