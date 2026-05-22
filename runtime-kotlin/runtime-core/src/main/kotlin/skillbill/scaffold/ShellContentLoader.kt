@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount", "TooManyFunctions")

package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Files
import java.nio.file.Path

internal fun loadPlatformManifest(packRoot: Path): PlatformManifest {
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val slug = resolvedPackRoot.fileName?.toString().orEmpty()
  val manifestPath = resolvedPackRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingManifestError("Platform pack '$slug': expected manifest at '$manifestPath' but it is missing.")
  }
  val raw = readManifest(manifestPath, slug)
  return buildPack(slug, resolvedPackRoot, manifestPath, raw)
}

internal fun loadPlatformPack(packRoot: Path): PlatformManifest {
  val pack = loadPlatformManifest(packRoot)
  validatePlatformPackCompositions(loadCompositionClosure(pack))
  validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
  pack.declaredQualityCheckFile?.let { loadQualityCheckContent(pack) }
  return pack
}

internal fun discoverPlatformPacks(platformPacksRoot: Path): List<PlatformManifest> {
  val packs = childDirectories(platformPacksRoot).map(::loadPlatformManifest)
  validatePlatformPackCompositions(packs)
  packs.forEach { pack ->
    validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
    pack.declaredQualityCheckFile?.let { loadQualityCheckContent(pack) }
  }
  return packs
}

internal fun discoverPlatformPackManifests(platformPacksRoot: Path): List<PlatformManifest> {
  val packs = childDirectories(platformPacksRoot).map(::loadPlatformManifest)
  validatePlatformPackCompositions(packs)
  return packs
}

fun discoverGovernedAddonFiles(repoRoot: Path): List<GovernedAddonFile> {
  val packsRoot = repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return emptyList()
  }
  return childDirectories(packsRoot).flatMap { packDir ->
    val addonsRoot = packDir.resolve("addons")
    if (!Files.isDirectory(addonsRoot)) {
      emptyList()
    } else {
      childMarkdownFiles(addonsRoot).map { addon -> GovernedAddonFile(packDir.fileName.toString(), addon) }
    }
  }
}

internal fun validatePlatformPack(pack: PlatformManifest, contractVersion: String) {
  // F-009: defense-in-depth. The canonical schema validator (run from
  // `buildPack` via `loadPlatformManifest`) already raises
  // `ContractVersionMismatchError` for any version drift, so callers that
  // skip the contract gate (e.g. `loadPlatformManifest`) still loud-fail.
  // Keep this duplicate check so any future caller that constructs a
  // `PlatformManifest` directly (bypassing the schema validator) is still
  // gated here.
  if (pack.contractVersion != contractVersion) {
    throw ContractVersionMismatchError(
      buildString {
        append("Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' ")
        append("but the shell expects '$contractVersion'.")
      },
    )
  }

  val declaredAreaFiles = pack.declaredFiles.areas
  val missingAreaSlots = pack.declaredCodeReviewAreas.toSet() - declaredAreaFiles.keys
  if (missingAreaSlots.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '${pack.slug}': declared_files.areas is missing entries for ${missingAreaSlots.sorted()}.",
    )
  }

  pack.declaredFiles.baseline?.let { baseline ->
    validateGovernedSkill(pack, "baseline", baseline, "code-review", "")
  }
  pack.declaredCodeReviewAreas.forEach { area ->
    validateGovernedSkill(pack, "areas.$area", declaredAreaFiles.getValue(area), "code-review", area)
  }
}

internal fun validatePlatformPackCompositions(packs: List<PlatformManifest>) {
  val packsBySlug = packs.associateBy { it.slug }
  packs
    .filter { it.codeReviewComposition != null }
    .forEach { pack -> validateCompositionReferences(pack, packsBySlug) }
  validateNoCompositionCycles(packs)
  packs
    .filter { it.codeReviewComposition != null }
    .forEach(::validateCompositionModeSupport)
}

private fun loadCompositionClosure(rootPack: PlatformManifest): List<PlatformManifest> {
  val packParent = rootPack.packRoot.parent
  return if (packParent == null || !Files.isDirectory(packParent)) {
    listOf(rootPack)
  } else {
    val loaded = linkedMapOf(rootPack.slug to rootPack)

    fun collect(pack: PlatformManifest) {
      pack.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
        if (layer.platform in loaded) {
          return@forEach
        }
        val targetRoot = packParent.resolve(layer.platform)
        if (!Files.isDirectory(targetRoot)) {
          return@forEach
        }
        val targetPack = loadPlatformManifest(targetRoot)
        loaded[targetPack.slug] = targetPack
        collect(targetPack)
      }
    }

    collect(rootPack)
    loaded.values.toList()
  }
}

private fun validateCompositionReferences(pack: PlatformManifest, packsBySlug: Map<String, PlatformManifest>) {
  val seenTargets = mutableSetOf<Pair<String, String>>()
  pack.codeReviewComposition?.baselineLayers.orEmpty().forEachIndexed { index, layer ->
    val targetLabel = "${layer.platform}/${layer.skill}"
    if (layer.platform == pack.slug) {
      throw InvalidManifestSchemaError(
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] self-references " +
          "the same platform pack '$targetLabel'.",
      )
    }
    if (!seenTargets.add(layer.platform to layer.skill)) {
      throw InvalidManifestSchemaError(
        "Platform pack '${pack.slug}': duplicate code_review_composition baseline layer '$targetLabel'.",
      )
    }
    val targetPack = packsBySlug[layer.platform]
      ?: throw InvalidManifestSchemaError(
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] references " +
          "missing platform pack '${layer.platform}'.",
      )
    if (layer.skill !in targetPack.declaredCodeReviewSkillNames()) {
      throw InvalidManifestSchemaError(
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] references " +
          "missing code-review skill '${layer.skill}' in platform pack '${layer.platform}'.",
      )
    }
  }
}

private fun validateCompositionModeSupport(pack: PlatformManifest) {
  pack.codeReviewComposition?.baselineLayers.orEmpty().forEachIndexed { index, layer ->
    validateCompositionModeSupport(pack.slug, index, layer)
  }
}

private fun validateCompositionModeSupport(sourceSlug: String, index: Int, layer: CodeReviewBaselineLayer) {
  val unsupportedReason = unsupportedCompositionModeReason(layer)
  if (unsupportedReason != null) {
    throw InvalidManifestSchemaError(
      "Platform pack '$sourceSlug': code_review_composition.baseline_layers[$index] uses mode " +
        "'${layer.mode.wireValue}' with unsupported referenced skill '${layer.platform}/${layer.skill}'. " +
        unsupportedReason,
    )
  }
}

internal fun unsupportedCompositionModeReason(layer: CodeReviewBaselineLayer): String? = when (layer.mode) {
  CodeReviewCompositionMode.KmpBaseline ->
    if (layer.platform == "kotlin" && layer.skill == "bill-kotlin-code-review") {
      null
    } else {
      "Mode '${layer.mode.wireValue}' is supported only for 'kotlin/bill-kotlin-code-review'."
    }
}

private fun validateNoCompositionCycles(packs: List<PlatformManifest>) {
  val graph: Map<String, List<String>> = packs.associate { pack ->
    pack.slug to pack.codeReviewComposition?.baselineLayers.orEmpty().map { layer -> layer.platform }
  }
  val visited = mutableSetOf<String>()
  val visiting = mutableSetOf<String>()
  val stack = mutableListOf<String>()

  fun visit(slug: String) {
    if (slug in visited) return
    if (slug in visiting) {
      val cycleStart = stack.indexOf(slug).coerceAtLeast(0)
      val cycle = (stack.drop(cycleStart) + slug).joinToString(" -> ")
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': code_review_composition contains a composition cycle: $cycle.",
      )
    }

    visiting += slug
    stack += slug
    graph.getValue(slug)
      .filter { target -> target in graph }
      .forEach(::visit)
    stack.removeAt(stack.lastIndex)
    visiting -= slug
    visited += slug
  }

  graph.keys.sorted().forEach(::visit)
}

internal fun PlatformManifest.declaredCodeReviewSkillNames(): Set<String> {
  val names = linkedSetOf<String>()
  routedSkillName?.let(names::add)
  declaredFiles.baseline?.parent?.fileName?.toString()
    ?.takeIf { it != "code-review" }
    ?.let(names::add)
  declaredCodeReviewAreas.forEach { area ->
    names += "bill-$slug-code-review-$area"
    declaredFiles.areas[area]?.parent?.fileName?.toString()
      ?.takeIf { it != "code-review" }
      ?.let(names::add)
  }
  return names
}

internal fun loadQualityCheckContent(pack: PlatformManifest): Path {
  val filePath = pack.declaredQualityCheckFile
    ?: throw MissingContentFileError(
      "Platform pack '${pack.slug}': declared_quality_check_file not set " +
        "(call is only valid after checking pack.declaredQualityCheckFile is not null).",
    )
  validateGovernedSkill(pack, "quality-check", filePath, "quality-check", "")
  return filePath
}

private fun readManifest(manifestPath: Path, slug: String): Any? = try {
  Yaml().load<Any?>(Files.readString(manifestPath))
} catch (error: Exception) {
  throw InvalidManifestSchemaError(
    "Platform pack '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
    error,
  )
}

private fun buildPack(slug: String, packRoot: Path, manifestPath: Path, raw: Any?): PlatformManifest {
  // SKILL-47: shape validation now flows through the canonical schema at
  // `orchestration/contracts/platform-pack-schema.yaml`. The Kotlin parser
  // below remains responsible for producing the typed `PlatformManifest`
  // and for the named coherence checks documented in the schema's
  // `x-coherence-checks` block (slug-parity, areas-require-baseline,
  // areas-equal-declared, area-metadata-keys-subset-declared,
  // pointers-unique-name-per-dir, addon-usage-*).
  val manifest = requireManifestMap(slug, manifestPath, raw)
  val typedManifest = validateAgainstCanonicalSchema(slug, manifest)

  val declaredPlatform = requireStringField(manifest, slug, "platform")
  if (declaredPlatform != slug) {
    // Coherence: slug-parity.
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': manifest 'platform' field is '$declaredPlatform', " +
        "expected '$slug' to match the directory name.",
    )
  }

  val contractVersion = requireStringField(manifest, slug, "contract_version")
  val routingSignals = parseRoutingSignals(manifest, slug)
  val declaredAreas = parseDeclaredAreas(manifest, slug)
  val declaredFiles = parseDeclaredFiles(manifest, slug, packRoot, declaredAreas)
  val areaMetadata = parseAreaMetadata(manifest, slug, declaredAreas)
  val displayName = parseOptionalString(manifest, slug, "display_name")
  val notes = parseOptionalString(manifest, slug, "notes")
  val declaredQualityCheckFile = parseOptionalPath(manifest, slug, "declared_quality_check_file", packRoot)
  val codeReviewComposition = parseCodeReviewComposition(manifest, slug)
  val pointers = parsePointers(manifest, slug)
  val addonUsage = parseAddonUsage(
    manifest = manifest,
    slug = slug,
    pointers = pointers,
    declaredSkillDirs = declaredSkillRelativeDirs(packRoot, declaredFiles, declaredQualityCheckFile),
  )

  // SKILL-48 Subtask 3: anchored top-level keys (those the runtime consumes by name) are
  // already captured in the typed fields above. Every remaining top-level YAML key flows
  // verbatim into `customFields` so repo authors can extend `platform.yaml` with
  // fork-specific fields without patching the canonical schema or the Kotlin runtime.
  // The anchored set is sourced from the schema (`x-runtime-anchored: true`) — never
  // hardcoded here — so the schema stays the single source of truth.
  val anchoredKeys = anchoredTopLevelFieldNames()
  val customFields: Map<String, Any?> = typedManifest.filterKeys { it !in anchoredKeys }

  // SKILL-48 A5(b): required anchored fields (e.g. `platform`, `routing_signals`) catch typos
  // via JSON Schema `required`, but OPTIONAL anchored fields (`display_name`, `notes`,
  // `declared_files`, `declared_quality_check_file`, `area_metadata`, `pointers`) do not —
  // a mis-spelled optional key would silently flow into `customFields` and the omitted
  // anchored field would just default. Walk every customFields key and loud-fail when it is
  // exactly one edit away from an anchored top-level field name. The check is case-sensitive
  // and runs entirely in Kotlin so the canonical schema stays unchanged.
  guardAgainstAnchoredFieldTypos(slug, manifestPath, customFields.keys, anchoredKeys)

  return PlatformManifest(
    slug = slug,
    packRoot = packRoot,
    contractVersion = contractVersion,
    routingSignals = routingSignals,
    declaredCodeReviewAreas = declaredAreas,
    declaredFiles = declaredFiles,
    areaMetadata = areaMetadata,
    displayName = displayName,
    notes = notes,
    declaredQualityCheckFile = declaredQualityCheckFile,
    codeReviewComposition = codeReviewComposition,
    pointers = pointers,
    addonUsage = addonUsage,
    customFields = customFields,
  )
}

private fun requireManifestMap(slug: String, manifestPath: Path, raw: Any?): Map<*, *> = raw as? Map<*, *>
  ?: throw InvalidManifestSchemaError(
    "Platform pack '$slug': manifest '$manifestPath' must be a YAML mapping at the top level.",
  )

// SKILL-48 A5(b): loud-fail when a top-level custom-field key looks like a typo of an
// anchored field. Iterates `anchoredKeys` in its existing (schema-property) order so the
// first near-match wins deterministically. Exact matches are skipped defensively — by
// construction `customFields` has already filtered them out, but the guard makes the
// intent explicit.
private fun guardAgainstAnchoredFieldTypos(
  slug: String,
  manifestPath: Path,
  customFieldKeys: Set<String>,
  anchoredKeys: Set<String>,
) {
  for (key in customFieldKeys) {
    for (anchored in anchoredKeys) {
      if (key == anchored) continue
      if (levenshtein1(key, anchored)) {
        throw InvalidManifestSchemaError(
          "Platform pack '$slug' ($manifestPath) has a top-level field '$key' that looks like a typo " +
            "of the anchored field '$anchored' (did you mean '$anchored'?). Remove or rename the field — " +
            "non-anchored fields flow through customFields, but anchored field names are reserved.",
        )
      }
    }
  }
}

// SKILL-48 A5(b): returns true iff `a` and `b` differ by exactly one edit
// (insertion, deletion, or substitution). Case-sensitive. Equal strings return
// false because they have edit distance 0, not 1.
private fun levenshtein1(a: String, b: String): Boolean {
  val lengthDelta = a.length - b.length
  if (lengthDelta < -1 || lengthDelta > 1 || a == b) return false
  return if (a.length == b.length) substitutionMatches(a, b) else insertionOrDeletionMatches(a, b)
}

// Substitution case (equal lengths): exactly one position must differ.
private fun substitutionMatches(a: String, b: String): Boolean {
  val diffs = a.indices.count { a[it] != b[it] }
  return diffs == 1
}

// Insertion/deletion case (lengths differ by 1): align the shorter string
// inside the longer string and allow one skipped character in the longer one.
private fun insertionOrDeletionMatches(a: String, b: String): Boolean {
  val longer = if (a.length > b.length) a else b
  val shorter = if (a.length > b.length) b else a
  var i = 0
  var j = 0
  var skipped = false
  while (i < longer.length && j < shorter.length) {
    if (longer[i] == shorter[j]) {
      i++
      j++
      continue
    }
    if (skipped) return false
    skipped = true
    i++
  }
  return true
}

// SKILL-47: shared validator instance. The validator caches its compiled
// schema; loading it once amortizes the cost across every pack load.
private val canonicalSchemaValidator: PlatformPackSchemaValidator by lazy {
  CanonicalPlatformPackSchemaValidator()
}

private fun validateAgainstCanonicalSchema(slug: String, manifest: Map<*, *>): Map<String, Any?> {
  // SKILL-48 C2: the validator's tightened signature requires `Map<String, Any?>`.
  // The YAML parser may legitimately surface non-string keys (e.g. `true:` or `1:` at
  // the top level). Convert them with a loud-fail so we never silently drop entries.
  val typedManifest: Map<String, Any?> = manifest.entries.associate { (key, value) ->
    val stringKey = key as? String
      ?: run {
        val keyType = key?.let { it::class.simpleName } ?: "null"
        throw InvalidManifestSchemaError(
          "Platform pack '$slug': manifest top-level keys must be strings, but found '$key' ($keyType).",
        )
      }
    stringKey to value
  }
  canonicalSchemaValidator.validate(typedManifest, slug)
  // SKILL-48 Subtask 3: callers reuse the validated typed map to derive `customFields` so
  // we do not re-walk the raw `Map<*, *>` and re-do the key shape check.
  return typedManifest
}

private fun parseRoutingSignals(manifest: Map<*, *>, slug: String): RoutingSignals {
  val routing = requireMappingField(manifest, slug, "routing_signals")
  val strongRaw = routing["strong"]
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest field 'routing_signals.strong' is required.")
  return RoutingSignals(
    strong = parseStringList(slug, strongRaw, "routing_signals.strong", required = true),
    tieBreakers = parseStringList(slug, routing["tie_breakers"], "routing_signals.tie_breakers", required = false),
  )
}

private fun parseDeclaredAreas(manifest: Map<*, *>, slug: String): List<String> {
  val rawAreas = requireField(manifest, slug, "declared_code_review_areas")
  if (rawAreas !is List<*>) {
    throw InvalidManifestSchemaError("Platform pack '$slug': 'declared_code_review_areas' must be a list.")
  }
  return rawAreas.map { entry ->
    val area = entry as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': every entry in 'declared_code_review_areas' must be a string.",
      )
    if (area !in APPROVED_CODE_REVIEW_AREAS) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': declared area '$area' is not approved; " +
          "must be one of ${APPROVED_CODE_REVIEW_AREAS.sorted()}.",
      )
    }
    area
  }
}

private fun parseDeclaredFiles(
  manifest: Map<*, *>,
  slug: String,
  packRoot: Path,
  declaredAreas: List<String>,
): DeclaredFiles {
  // `declared_files` is optional: a platform pack may ship with no code-review feature at all
  // (e.g. quality-check-only or addons-only). When the block is missing we treat baseline and
  // areas as empty; the consumers null-check the baseline before composing code-review artifacts.
  val rawFiles = (manifest["declared_files"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
  val baselineRaw = rawFiles["baseline"] as? String
  val baselinePath = baselineRaw?.let {
    if (it.isBlank()) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'declared_files.baseline' must be a non-empty path string when present.",
      )
    }
    // SKILL-48 C1: the `content.md`-suffix check on this field is owned by the canonical
    // schema (`declared_files.baseline.pattern: "(^|/)content\\.md$"`); no Kotlin-side
    // duplicate is needed because callers reach this only through `loadPlatformPack` /
    // `loadPlatformManifest`, both of which run the schema validator first.
    packRoot.resolve(it).normalize()
  }

  val rawAreaFiles = rawFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
  val areaFiles = rawAreaFiles.entries.associate { (key, value) ->
    val area = key as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'declared_files.areas' entries must be string->string.",
      )
    val relativePath = value as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'declared_files.areas' entries must be string->string.",
      )
    // SKILL-48 C1: `content.md`-suffix is owned by the canonical schema
    // (`declared_files.areas.additionalProperties.pattern: "(^|/)content\\.md$"`).
    area to packRoot.resolve(relativePath).normalize()
  }

  val extraAreaKeys = areaFiles.keys - declaredAreas.toSet()
  if (extraAreaKeys.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'declared_files.areas' contains entries ${extraAreaKeys.sorted()} " +
        "that are not listed in 'declared_code_review_areas'.",
    )
  }
  val missingAreaKeys = declaredAreas.toSet() - areaFiles.keys
  if (missingAreaKeys.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'declared_files.areas' is missing entries for ${missingAreaKeys.sorted()}.",
    )
  }

  // Coherence: if the pack declares code-review areas but no baseline, the manifest is inconsistent.
  // The baseline is what the area specialists override, so areas without a baseline are meaningless.
  if (baselinePath == null && areaFiles.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'declared_files.areas' is set but 'declared_files.baseline' is missing.",
    )
  }
  return DeclaredFiles(
    baseline = baselinePath,
    areas = areaFiles,
  )
}

private fun parseAreaMetadata(manifest: Map<*, *>, slug: String, declaredAreas: List<String>): Map<String, String> {
  // Optional: a pack with no code-review areas does not need an area_metadata block.
  val rawMetadata = (manifest["area_metadata"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
  val areaMetadata = mutableMapOf<String, String>()
  val extraAreaMetadata = mutableSetOf<String>()
  for ((key, value) in rawMetadata) {
    val area = key as? String
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': area_metadata entries must be string -> mapping.")
    if (area !in declaredAreas) {
      extraAreaMetadata += area
      continue
    }
    val metadata = value as? Map<*, *>
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': area_metadata['$area'] must be a mapping.")
    val focus = metadata["focus"] as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': area_metadata['$area'].focus must be a non-empty string.",
      )
    areaMetadata[area] = focus
  }
  if (extraAreaMetadata.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': area_metadata contains entries ${extraAreaMetadata.sorted()} " +
        "that are not listed in 'declared_code_review_areas'.",
    )
  }
  declaredAreas.forEach { declaredArea -> areaMetadata.putIfAbsent(declaredArea, defaultAreaFocus(declaredArea)) }
  return areaMetadata
}

private fun parseCodeReviewComposition(manifest: Map<*, *>, slug: String): CodeReviewComposition? {
  val raw = manifest["code_review_composition"] ?: return null
  val composition = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'code_review_composition' must be a mapping when provided.",
    )
  val layersRaw = composition["baseline_layers"]
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'code_review_composition.baseline_layers' is required.",
    )
  val layers = layersRaw as? List<*>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'code_review_composition.baseline_layers' must be a list.",
    )
  return CodeReviewComposition(
    baselineLayers = layers.mapIndexed { index, entry -> parseCodeReviewBaselineLayer(slug, index, entry) },
  )
}

private fun parseCodeReviewBaselineLayer(slug: String, index: Int, raw: Any?): CodeReviewBaselineLayer {
  val layer = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'code_review_composition.baseline_layers[$index]' must be a mapping.",
    )
  val fieldPrefix = "code_review_composition.baseline_layers[$index]"
  val scopeValue = requireStringInMap(slug, layer, "$fieldPrefix.scope", "scope")
  val modeValue = requireStringInMap(slug, layer, "$fieldPrefix.mode", "mode")
  val required = layer["required"] as? Boolean
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldPrefix.required' must be an explicit boolean.")

  return CodeReviewBaselineLayer(
    platform = requireStringInMap(slug, layer, "$fieldPrefix.platform", "platform"),
    skill = requireStringInMap(slug, layer, "$fieldPrefix.skill", "skill"),
    scope = CodeReviewCompositionScope.fromWireValue(scopeValue)
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': '$fieldPrefix.scope' has unsupported value '$scopeValue'.",
      ),
    required = required,
    mode = CodeReviewCompositionMode.fromWireValue(modeValue)
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': '$fieldPrefix.mode' has unsupported value '$modeValue'.",
      ),
  )
}

private fun requireStringInMap(slug: String, map: Map<*, *>, fieldLabel: String, key: String): String {
  val value = map[key] as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must be a non-empty string.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must be a non-empty string.")
  }
  return value
}

private fun parseOptionalString(manifest: Map<*, *>, slug: String, key: String): String? = manifest[key]?.let {
  it as? String ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a string when provided.")
}

private fun parsePointers(manifest: Map<*, *>, slug: String): List<PointerSpec> {
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

private fun parseAddonUsage(
  manifest: Map<*, *>,
  slug: String,
  pointers: List<PointerSpec>,
  declaredSkillDirs: Set<String>,
): List<GovernedAddonUsage> {
  val raw = manifest["addon_usage"] ?: return emptyList()
  val usageMap = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'addon_usage' must be a mapping of skill-relative-dir to add-on entries.",
    )
  val pointersByDir = pointers.groupBy { spec -> spec.skillRelativeDir }
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
    if (skillRelativeDir !in declaredSkillDirs) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'addon_usage' key '$skillRelativeDir' must match a declared skill directory. " +
          "Declared skill directories: ${declaredSkillDirs.sorted()}.",
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
      seenSlugs = mutableSetOf(),
      pointersForDir = pointersByDir[skillRelativeDir].orEmpty(),
    )
    val addons = entriesList.mapIndexed { index, entry ->
      parseAddonUsageEntry(context, index, entry)
    }
    GovernedAddonUsage(skillRelativeDir = skillRelativeDir, addons = addons)
  }
}

private data class AddonUsageParseContext(
  val slug: String,
  val skillRelativeDir: String,
  val seenSlugs: MutableSet<String>,
  val pointersForDir: List<PointerSpec>,
)

private fun parseAddonUsageEntry(context: AddonUsageParseContext, index: Int, raw: Any?): GovernedAddonSelection {
  val entry = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': 'addon_usage[${context.skillRelativeDir}][$index]' must be a mapping.",
    )
  val fieldPrefix = "addon_usage[${context.skillRelativeDir}][$index]"
  val addonSlug = requireStringInMap(context.slug, entry, "$fieldPrefix.slug", "slug")
  if (!context.seenSlugs.add(addonSlug)) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': duplicate add-on usage slug '$addonSlug' under " +
        "'${context.skillRelativeDir}'.",
    )
  }
  val entrypoint = requireStringInMap(context.slug, entry, "$fieldPrefix.entrypoint", "entrypoint")
  val companionPointers = parseStringList(
    context.slug,
    entry["companion_pointers"],
    "$fieldPrefix.companion_pointers",
    required = false,
  )
  requirePackOwnedAddonPointer(context, addonSlug, "entrypoint", entrypoint)
  companionPointers.forEach { pointerName ->
    requirePackOwnedAddonPointer(context, addonSlug, "companion_pointers", pointerName)
  }
  return GovernedAddonSelection(
    slug = addonSlug,
    entrypoint = entrypoint,
    companionPointers = companionPointers,
  )
}

private fun requirePackOwnedAddonPointer(
  context: AddonUsageParseContext,
  addonSlug: String,
  field: String,
  pointerName: String,
) {
  val pointer = context.pointersForDir.firstOrNull { spec -> spec.name == pointerName }
    ?: throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': addon_usage[${context.skillRelativeDir}] entry '$addonSlug' references " +
        "$field '$pointerName', but pointers[${context.skillRelativeDir}] does not declare that pointer.",
    )
  val expectedPrefix = "platform-packs/${context.slug}/addons/"
  if (!pointer.target.startsWith(expectedPrefix) || !pointer.target.endsWith(".md")) {
    throw InvalidManifestSchemaError(
      "Platform pack '${context.slug}': addon_usage[${context.skillRelativeDir}] entry '$addonSlug' " +
        "references pointer '$pointerName', but its target '${pointer.target}' is not under '$expectedPrefix'.",
    )
  }
}

private fun parsePointerEntry(slug: String, skillRelativeDir: String, entry: Map<*, *>): PointerSpec {
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
private fun requireSafePointerSubpath(slug: String, value: String, label: String) {
  if (value.startsWith("/") || value.startsWith("\\")) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': $label '$value' must be a relative path (no leading '/').",
    )
  }
  val asPath = try {
    java.nio.file.Path.of(value)
  } catch (error: java.nio.file.InvalidPathException) {
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
private fun requireSafePointerTarget(slug: String, skillRelativeDir: String, name: String, target: String) {
  if (target.startsWith("/") || target.startsWith("\\")) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must be a " +
        "repo-relative path (no leading '/').",
    )
  }
  val asPath = try {
    java.nio.file.Path.of(target)
  } catch (error: java.nio.file.InvalidPathException) {
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

private fun parseOptionalPath(manifest: Map<*, *>, slug: String, key: String, packRoot: Path): Path? {
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

private fun requireMappingField(manifest: Map<*, *>, slug: String, key: String): Map<*, *> = manifest[key] as? Map<*, *>
  ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest field '$key' must be a mapping.")

private fun requireField(manifest: Map<*, *>, slug: String, key: String): Any =
  manifest[key] ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest is missing required field '$key'.")

private fun requireStringField(manifest: Map<*, *>, slug: String, key: String): String {
  val value = requireField(manifest, slug, key) as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a string.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty string.")
  }
  return value
}

private fun parseStringList(slug: String, value: Any?, fieldLabel: String, required: Boolean): List<String> {
  if (value == null) {
    return emptyList()
  }
  if (value !is List<*>) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must be a list of strings.")
  }
  val parsed = value.map { entry ->
    entry as? String
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': every entry in '$fieldLabel' must be a string.")
  }
  if (required && parsed.isEmpty()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must contain at least one routing signal.")
  }
  return parsed
}

private fun validateGovernedSkill(
  pack: PlatformManifest,
  slot: String,
  skillPath: Path,
  @Suppress("UNUSED_PARAMETER") family: String,
  @Suppress("UNUSED_PARAMETER") area: String,
) {
  if (skillPath.fileName?.toString() != CONTENT_BODY_FILENAME) {
    throw InvalidManifestSchemaError(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' must end in " +
        "'$CONTENT_BODY_FILENAME' but was '${displayPackPath(pack, skillPath)}'.",
    )
  }
  if (!Files.isRegularFile(skillPath)) {
    throw MissingContentFileError(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' is missing at '$skillPath'.",
    )
  }
  val text = Files.readString(skillPath)
  validateSkillMdShape(skillPath, validateBodyShape = false)
  ensureValidAuthoredContent(pack.slug, skillPath, text)
}

private fun ensureValidAuthoredContent(slug: String, skillPath: Path, text: String) {
  val authoredIssues = validateAuthoredContent(skillPath, text)
  if (authoredIssues.isNotEmpty()) {
    throw MissingRequiredSectionError(
      "Platform pack '$slug': ${authoredIssues.first()}",
    )
  }
}

private fun displayPackPath(pack: PlatformManifest, path: Path): String = runCatching {
  pack.packRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
    .toString()
    .replace('\\', '/')
}.getOrDefault(path.toString())

private fun childDirectories(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return emptyList()
  }
  return Files.list(root).use { stream ->
    stream
      .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
      .toList()
      .sortedBy { it.fileName.toString() }
  }
}

private fun childMarkdownFiles(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return emptyList()
  }
  return Files.list(root).use { stream ->
    stream
      .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
      .toList()
      .sortedBy { it.fileName.toString() }
  }
}
