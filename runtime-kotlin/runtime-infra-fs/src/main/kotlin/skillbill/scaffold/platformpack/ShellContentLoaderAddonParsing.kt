
package skillbill.scaffold.platformpack

import skillbill.scaffold.model.FeatureAddonUsage
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path

internal data class AddonUsageManifestContext(
  val slug: String,
  val packRoot: Path,
  val pointers: List<PointerSpec>,
  val declaredSkillDirs: Set<String>,
  val declaredAreas: Set<String>,
  val strictReviewRouting: Boolean = true,
)

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

internal fun parseAddonUsage(
  manifest: Map<*, *>,
  manifestContext: AddonUsageManifestContext,
): List<GovernedAddonUsage> {
  val slug = manifestContext.slug
  val raw = manifest["addon_usage"] ?: return emptyList()
  val usageMap = raw as? Map<*, *>
    ?: invalidManifestSchema(
      "Platform pack '$slug': 'addon_usage' must be a mapping of skill-relative-dir to add-on entries.",
    )
  val pointersByDir = manifestContext.pointers.groupBy { spec -> spec.skillRelativeDir }
  return usageMap.map { (dirKey, entriesRaw) ->
    val skillRelativeDir = dirKey as? String
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'addon_usage' keys must be strings (skill-relative directory paths).",
      )
    if (skillRelativeDir.isBlank()) {
      invalidManifestSchema(
        "Platform pack '$slug': 'addon_usage' skill-relative directory must be a non-empty string.",
      )
    }
    requireSafePointerSubpath(slug, skillRelativeDir, "addon_usage skill-relative directory")
    if (skillRelativeDir !in manifestContext.declaredSkillDirs) {
      invalidManifestSchema(
        "Platform pack '$slug': 'addon_usage' key '$skillRelativeDir' must match a declared skill directory. " +
          "Declared skill directories: ${manifestContext.declaredSkillDirs.sorted()}.",
      )
    }
    val entriesList = entriesRaw as? List<*>
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'addon_usage[$skillRelativeDir]' must be a list of add-on entries.",
      )
    if (entriesList.isEmpty()) {
      invalidManifestSchema(
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
    ?: invalidManifestSchema(
      "Platform pack '$slug': 'feature_addon_usage' must be a mapping of feature-task consumer to add-on entries.",
    )
  val pointersByConsumer = pointers.groupBy { spec -> spec.skillRelativeDir }
  return usageMap.map { (consumerKey, entriesRaw) ->
    val consumer = consumerKey as? String
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'feature_addon_usage' keys must be strings (feature-task consumers).",
      )
    if (consumer != FEATURE_TASK_ADDON_CONSUMER) {
      invalidManifestSchema(
        "Platform pack '$slug': 'feature_addon_usage' key '$consumer' must be '$FEATURE_TASK_ADDON_CONSUMER'.",
      )
    }
    val entriesList = entriesRaw as? List<*>
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'feature_addon_usage[$consumer]' must be a list of add-on entries.",
      )
    if (entriesList.isEmpty()) {
      invalidManifestSchema(
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
    invalidManifestSchema(
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
    ?: invalidManifestSchema(
      "Platform pack '${context.slug}': '${context.fieldName}[${context.skillRelativeDir}][$index]' must be a mapping.",
    )

internal fun parseAddonSlug(context: AddonUsageParseContext, entry: Map<*, *>, fieldPrefix: String): String {
  val addonSlug = requireStringInMap(context.slug, entry, "$fieldPrefix.slug", "slug")
  if (!context.seenSlugs.add(addonSlug)) {
    invalidManifestSchema(
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
    invalidManifestSchema(
      "Platform pack '${context.slug}': '$fieldPrefix.specialist_areas' names undeclared areas " +
        "${unknownSpecialistAreas.sorted()}.",
    )
  }
  val baselineReviewAddon = context.skillRelativeDir.endsWith("-code-review")
  val requiresSpecialistAreas = reviewAddon && context.strictReviewRouting && baselineReviewAddon
  if (requiresSpecialistAreas && specialistAreas.isEmpty()) {
    invalidManifestSchema(
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
    ?: invalidManifestSchema(
      "Platform pack '${context.slug}': ${context.fieldName}[${context.skillRelativeDir}] entry '$addonSlug' " +
        "references $field '$pointerName', but pointers[${context.skillRelativeDir}] does not declare that pointer.",
    )
  val expectedPrefix = "platform-packs/${context.slug}/addons/"
  if (!pointer.target.startsWith(expectedPrefix) || !pointer.target.endsWith(".md")) {
    invalidManifestSchema(
      "Platform pack '${context.slug}': ${context.fieldName}[${context.skillRelativeDir}] entry '$addonSlug' " +
        "references pointer '$pointerName', but its target '${pointer.target}' is not under '$expectedPrefix'.",
    )
  }
  if (context.fieldName == "feature_addon_usage") {
    val repoRoot = context.packRoot.parent?.parent
      ?: invalidManifestSchema(
        "Platform pack '${context.slug}': cannot resolve repo root for ${context.fieldName} pointer '$pointerName'.",
      )
    val targetFile = repoRoot.resolve(pointer.target).normalize()
    if (!Files.isRegularFile(targetFile)) {
      invalidManifestSchema(
        "Platform pack '${context.slug}': ${context.fieldName}[${context.skillRelativeDir}] entry '$addonSlug' " +
          "references pointer '$pointerName' target '${pointer.target}', but that add-on file does not exist.",
      )
    }
  }
}
