@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount", "TooManyFunctions")

package skillbill.scaffold.platformpack

import skillbill.error.InvalidManifestSchemaError
import skillbill.scaffold.model.FeatureAddonUsage
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun parsePointers(manifest: Map<*, *>, slug: String): List<PointerSpec> {
  val raw = manifest["pointers"] ?: return emptyList()
  val pointersMap = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'pointers' must be a mapping of skill-relative-dir to a list of pointer entries.",
    )
  val seen = mutableSetOf<Pair<String, String>>()
  val collected = mutableListOf<PointerSpec>()
  for ((dirKey, entriesRaw) in pointersMap) {
    val skillRelativeDir = dirKey as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'pointers' keys must be strings (skill-relative directory paths).",
      )
    if (skillRelativeDir.isBlank()) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'pointers' skill-relative directory must be a non-empty string.",
      )
    }
    requireSafePointerSubpath(slug, skillRelativeDir, "pointers skill-relative directory")
    val entriesList = entriesRaw as? List<*>
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'pointers[$skillRelativeDir]' must be a list of {name, target} entries.",
      )
    for (entry in entriesList) {
      val entryMap = entry as? Map<*, *>
        ?: throw InvalidManifestSchemaError(
          "Platform pack '$slug': 'pointers[$skillRelativeDir]' entries must be mappings with name and target.",
        )
      val spec = parsePointerEntry(slug, skillRelativeDir, entryMap)
      val key = spec.skillRelativeDir to spec.name
      if (!seen.add(key)) {
        throw InvalidManifestSchemaError(
          "Platform pack '$slug': duplicate pointer entry '${spec.name}' under '${spec.skillRelativeDir}'.",
        )
      }
      collected += spec
    }
  }
  return collected
}

internal data class AddonUsageManifestContext(
  val slug: String,
  val packRoot: Path,
  val pointers: List<PointerSpec>,
  val declaredSkillDirs: Set<String>,
  val declaredAreas: Set<String>,
  val strictReviewRouting: Boolean = true,
)

internal fun parseAddonUsage(
  manifest: Map<*, *>,
  manifestContext: AddonUsageManifestContext,
): List<GovernedAddonUsage> {
  val slug = manifestContext.slug
  val raw = manifest["addon_usage"] ?: return emptyList()
  val usageMap = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'addon_usage' must be a mapping of skill-relative-dir to add-on entries.",
    )
  val pointersByDir = manifestContext.pointers.groupBy { spec -> spec.skillRelativeDir }
  return usageMap.map { (dirKey, entriesRaw) ->
    val skillRelativeDir = dirKey as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'addon_usage' keys must be strings (skill-relative directory paths).",
      )
    if (skillRelativeDir.isBlank()) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'addon_usage' skill-relative directory must be a non-empty string.",
      )
    }
    requireSafePointerSubpath(slug, skillRelativeDir, "addon_usage skill-relative directory")
    if (skillRelativeDir !in manifestContext.declaredSkillDirs) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'addon_usage' key '$skillRelativeDir' must match a declared skill directory. " +
          "Declared skill directories: ${manifestContext.declaredSkillDirs.sorted()}.",
      )
    }
    val entriesList = entriesRaw as? List<*>
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'addon_usage[$skillRelativeDir]' must be a list of add-on entries.",
      )
    if (entriesList.isEmpty()) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'addon_usage[$skillRelativeDir]' must declare at least one add-on entry.",
      )
    }
    val context = AddonUsageParseContext(
      slug = slug,
      skillRelativeDir = skillRelativeDir,
      packRoot = manifestContext.packRoot,
      fieldName = "addon_usage",
      seenSlugs = mutableSetOf(),
      pointersForDir = pointersByDir[skillRelativeDir].orEmpty(),
      declaredAreas = manifestContext.declaredAreas,
      strictReviewRouting = manifestContext.strictReviewRouting,
    )
    val addons = entriesList.mapIndexed { index, entry ->
      parseAddonUsageEntry(context, index, entry)
    }
    GovernedAddonUsage(skillRelativeDir = skillRelativeDir, addons = addons)
  }
}

internal fun parseFeatureAddonUsage(
  manifest: Map<*, *>,
  slug: String,
  packRoot: Path,
  pointers: List<PointerSpec>,
): List<FeatureAddonUsage> {
  val raw = manifest["feature_addon_usage"] ?: return emptyList()
  val usageMap = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'feature_addon_usage' must be a mapping of feature-task consumer to add-on entries.",
    )
  val pointersByConsumer = pointers.groupBy { spec -> spec.skillRelativeDir }
  return usageMap.map { (consumerKey, entriesRaw) ->
    val consumer = consumerKey as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'feature_addon_usage' keys must be strings (feature-task consumers).",
      )
    if (consumer != FEATURE_TASK_ADDON_CONSUMER) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'feature_addon_usage' key '$consumer' must be '$FEATURE_TASK_ADDON_CONSUMER'.",
      )
    }
    val entriesList = entriesRaw as? List<*>
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'feature_addon_usage[$consumer]' must be a list of add-on entries.",
      )
    if (entriesList.isEmpty()) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'feature_addon_usage[$consumer]' must declare at least one add-on entry.",
      )
    }
    val context = AddonUsageParseContext(
      slug = slug,
      skillRelativeDir = consumer,
      packRoot = packRoot,
      fieldName = "feature_addon_usage",
      seenSlugs = mutableSetOf(),
      pointersForDir = pointersByConsumer[consumer].orEmpty(),
      declaredAreas = emptySet(),
      strictReviewRouting = false,
    )
    val addons = entriesList.mapIndexed { index, entry ->
      parseAddonUsageEntry(context, index, entry)
    }
    FeatureAddonUsage(consumer = consumer, addons = addons)
  }
}

internal data class AddonUsageParseContext(
  val slug: String,
  val skillRelativeDir: String,
  val packRoot: Path,
  val fieldName: String,
  val seenSlugs: MutableSet<String>,
  val pointersForDir: List<PointerSpec>,
  val declaredAreas: Set<String>,
  val strictReviewRouting: Boolean,
)

internal fun parseAddonUsageEntry(context: AddonUsageParseContext, index: Int, raw: Any?): GovernedAddonSelection {
  val entry = requireAddonUsageEntry(context, index, raw)
  val fieldPrefix = "${context.fieldName}[${context.skillRelativeDir}][$index]"
  val addonSlug = parseAddonSlug(context, entry, fieldPrefix)
  val entrypoint = requireStringInMap(context.slug, entry, "$fieldPrefix.entrypoint", "entrypoint")
  val companionPointers = parseStringList(
    context.slug,
    entry["companion_pointers"],
    "$fieldPrefix.companion_pointers",
    required = false,
  )
  val activation = parseAddonActivation(context, entry, fieldPrefix)
  val reviewAddon = isReviewAddon(context)
  if (reviewAddon && context.strictReviewRouting && activation == null) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': '$fieldPrefix.activation' is required for governed review add-ons.",
    )
  }
  val specialistAreas = parseSpecialistAreas(context, entry, fieldPrefix, reviewAddon)
  requirePackOwnedAddonPointer(context, addonSlug, "entrypoint", entrypoint)
  companionPointers.forEach { pointerName ->
    requirePackOwnedAddonPointer(context, addonSlug, "companion_pointers", pointerName)
  }
  return GovernedAddonSelection(
    slug = addonSlug,
    entrypoint = entrypoint,
    companionPointers = companionPointers,
    activation = activation,
    specialistAreas = specialistAreas,
  )
}

internal fun requireAddonUsageEntry(context: AddonUsageParseContext, index: Int, raw: Any?): Map<*, *> =
  raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': '${context.fieldName}[${context.skillRelativeDir}][$index]' must be a mapping.",
    )

internal fun parseAddonSlug(context: AddonUsageParseContext, entry: Map<*, *>, fieldPrefix: String): String {
  val addonSlug = requireStringInMap(context.slug, entry, "$fieldPrefix.slug", "slug")
  if (!context.seenSlugs.add(addonSlug)) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': duplicate add-on usage slug '$addonSlug' under " +
        "'${context.fieldName}.${context.skillRelativeDir}'.",
    )
  }
  return addonSlug
}

internal fun parseAddonActivation(
  context: AddonUsageParseContext,
  entry: Map<*, *>,
  fieldPrefix: String,
): GovernedAddonActivation? {
  val rawActivation = entry["activation"] as? Map<*, *> ?: return null
  fun signals(field: String) = parseStringList(
    context.slug,
    rawActivation[field],
    "$fieldPrefix.activation.$field",
    required = false,
  )
  val anyOfAllContent = (rawActivation["any_of_all_content"] as? List<*>)?.mapIndexed { groupIndex, group ->
    parseStringList(
      context.slug,
      group,
      "$fieldPrefix.activation.any_of_all_content[$groupIndex]",
      required = true,
    )
  }.orEmpty()
  return GovernedAddonActivation(
    anyPath = signals("any_path"),
    anyContent = signals("any_content"),
    allContent = signals("all_content"),
    anyOfAllContent = anyOfAllContent,
    excludePath = signals("exclude_path"),
    excludeContent = signals("exclude_content"),
  )
}

internal fun parseSpecialistAreas(
  context: AddonUsageParseContext,
  entry: Map<*, *>,
  fieldPrefix: String,
  reviewAddon: Boolean,
): List<String> {
  val specialistAreas = parseStringList(
    context.slug,
    entry["specialist_areas"],
    "$fieldPrefix.specialist_areas",
    required = false,
  )
  val unknownSpecialistAreas = specialistAreas.toSet() - context.declaredAreas
  if (reviewAddon && unknownSpecialistAreas.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': '$fieldPrefix.specialist_areas' names undeclared areas " +
        "${unknownSpecialistAreas.sorted()}.",
    )
  }
  val baselineReviewAddon = context.skillRelativeDir.endsWith("-code-review")
  val requiresSpecialistAreas = reviewAddon && context.strictReviewRouting && baselineReviewAddon
  if (requiresSpecialistAreas && specialistAreas.isEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': '$fieldPrefix.specialist_areas' is required for baseline add-on propagation.",
    )
  }
  return specialistAreas
}

internal fun isReviewAddon(context: AddonUsageParseContext): Boolean =
  context.fieldName == "addon_usage" && context.skillRelativeDir.startsWith("code-review/")

internal fun requirePackOwnedAddonPointer(
  context: AddonUsageParseContext,
  addonSlug: String,
  field: String,
  pointerName: String,
) {
  val pointer = context.pointersForDir.firstOrNull { spec -> spec.name == pointerName }
    ?: throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': ${context.fieldName}[${context.skillRelativeDir}] entry '$addonSlug' " +
        "references $field '$pointerName', but pointers[${context.skillRelativeDir}] does not declare that pointer.",
    )
  val expectedPrefix = "platform-packs/${context.slug}/addons/"
  if (!pointer.target.startsWith(expectedPrefix) || !pointer.target.endsWith(".md")) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': ${context.fieldName}[${context.skillRelativeDir}] entry '$addonSlug' " +
        "references pointer '$pointerName', but its target '${pointer.target}' is not under '$expectedPrefix'.",
    )
  }
  if (context.fieldName == "feature_addon_usage") {
    val repoRoot = context.packRoot.parent?.parent
      ?: throw InvalidManifestSchemaError(
        "Platform pack '${context.slug}': cannot resolve repo root for ${context.fieldName} pointer '$pointerName'.",
      )
    val targetFile = repoRoot.resolve(pointer.target).normalize()
    if (!Files.isRegularFile(targetFile)) {
      throw InvalidManifestSchemaError(
        "Platform pack '${context.slug}': ${context.fieldName}[${context.skillRelativeDir}] entry '$addonSlug' " +
          "references pointer '$pointerName' target '${pointer.target}', but that add-on file does not exist.",
      )
    }
  }
}

internal const val FEATURE_TASK_ADDON_CONSUMER: String = "feature-task"

internal fun parsePointerEntry(slug: String, skillRelativeDir: String, entry: Map<*, *>): PointerSpec {
  val name = entry["name"] as? String
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'pointers[$skillRelativeDir]' entry is missing string field 'name'.",
    )
  val target = entry["target"] as? String
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'pointers[$skillRelativeDir]' entry '$name' is missing string field 'target'.",
    )
  // SKILL-48 C1: the `.md`-suffix, no-`..`, and no-`/` checks on `name` are already enforced
  // by the canonical schema's pointer `name` pattern (`^[^/\\\\]+\\.md$` plus `not: { pattern: "\\.\\." }`).
  // Removing them here drops duplicated Kotlin-side validation; the schema validator's loud-fail
  // message names the same field path so the failure UX does not regress.
  if (target.isBlank()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' must declare a non-empty 'target'.",
    )
  }
  requireSafePointerTarget(slug, skillRelativeDir, name, target)
  return PointerSpec(skillRelativeDir = skillRelativeDir, name = name, target = target)
}

// SKILL-48 C1: kept on purpose. The canonical schema's pointer `name` pattern guards bare
// filenames, but it does NOT fully express the semantics enforced below for the skill-relative
// directory key and pointer target: absolute-vs-relative paths, `..` segment rejection, and
// JVM-`Path` parsability. Keeping these as Kotlin checks preserves defense-in-depth for any
// future caller that bypasses the schema validator and lets us surface richer, field-specific
// error messages than the generic schema-validator output.
internal fun requireSafePointerSubpath(slug: String, value: String, label: String) {
  if (value.startsWith("/") || value.startsWith("\\")) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': $label '$value' must be a relative path (no leading '/').",
    )
  }
  val asPath = try {
    Path.of(value)
  } catch (error: InvalidPathException) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': $label '$value' is not a valid path: ${error.message}",
      error,
    )
  }
  if (asPath.isAbsolute) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': $label '$value' must be relative, not absolute.",
    )
  }
  asPath.iterator().forEachRemaining { segment ->
    if (segment.toString() == "..") {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': $label '$value' must not contain '..' segments.",
      )
    }
  }
}

// SKILL-48 C1: kept on purpose for the same reasons as `requireSafePointerSubpath` — schema
// expresses `target` as a non-empty string, but absolute-path / `..`-segment / Path parsability
// semantics live here so we can produce field-named loud-fail messages.
internal fun requireSafePointerTarget(slug: String, skillRelativeDir: String, name: String, target: String) {
  if (target.startsWith("/") || target.startsWith("\\")) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must be a " +
        "repo-relative path (no leading '/').",
    )
  }
  val asPath = try {
    Path.of(target)
  } catch (error: InvalidPathException) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' is not a valid path: " +
        "${error.message}",
      error,
    )
  }
  if (asPath.isAbsolute) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must be a " +
        "repo-relative path, not absolute.",
    )
  }
  asPath.iterator().forEachRemaining { segment ->
    if (segment.toString() == "..") {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must not contain " +
          "'..' segments.",
      )
    }
  }
}

internal fun parseOptionalPath(manifest: Map<*, *>, slug: String, key: String, packRoot: Path): Path? {
  val raw = manifest[key] ?: return null
  val value = raw as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  }
  // SKILL-48 C1: `content.md`-suffix for `declared_quality_check_file` is owned by the schema
  // (`pattern: "(^|/)content\\.md$"`).
  return packRoot.resolve(value).normalize()
}
