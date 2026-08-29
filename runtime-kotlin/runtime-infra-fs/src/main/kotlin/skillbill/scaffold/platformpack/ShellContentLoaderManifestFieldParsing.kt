@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount")

package skillbill.scaffold.platformpack

import skillbill.error.InvalidManifestSchemaError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import java.nio.file.Path

internal fun parseRoutingSignals(
  manifest: Map<*, *>,
  slug: String,
  requirePath: Boolean,
  fallbackOnly: Boolean,
): RoutingSignals {
  val routing = requireMappingField(manifest, slug, "routing_signals")
  val strongRaw = routing["strong"]
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest field 'routing_signals.strong' is required.")
  if (requirePath && !fallbackOnly && routing["path"] == null) {
    throw InvalidManifestSchemaError("Platform pack '$slug': manifest field 'routing_signals.path' is required.")
  }
  return RoutingSignals(
    strong = parseStringList(slug, strongRaw, "routing_signals.strong", required = true),
    tieBreakers = parseStringList(slug, routing["tie_breakers"], "routing_signals.tie_breakers", required = false),
    path = parseStringList(
      slug,
      routing["path"] ?: strongRaw.takeUnless { requirePath || fallbackOnly },
      "routing_signals.path",
      required = !fallbackOnly,
    ),
    content = parseStringList(slug, routing["content"], "routing_signals.content", required = false),
  )
}

internal fun parseLaneConditions(
  manifest: Map<*, *>,
  slug: String,
  declaredAreas: List<String>,
): Map<String, ReviewLaneCondition> {
  val raw = manifest["lane_conditions"] ?: return emptyMap()
  val mapping = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': 'lane_conditions' must be a mapping.")
  val parsed = mapping.map { (areaRaw, conditionRaw) ->
    val area = areaRaw as? String
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': lane condition areas must be strings.")
    if (area !in declaredAreas) {
      throw InvalidManifestSchemaError("Platform pack '$slug': lane condition '$area' is not a declared area.")
    }
    val condition = conditionRaw as? Map<*, *>
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': lane condition '$area' must be a mapping.")
    area to ReviewLaneCondition(
      required = condition["required"] as? Boolean ?: false,
      path = parseStringList(slug, condition["path"], "lane_conditions.$area.path", required = false),
      content = parseStringList(slug, condition["content"], "lane_conditions.$area.content", required = false),
    )
  }.toMap()
  val missing = declaredAreas.toSet() - parsed.keys
  if (missing.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'lane_conditions' is missing declared areas ${missing.sorted()}.",
    )
  }
  return parsed
}

internal fun parseDeclaredAreas(manifest: Map<*, *>, slug: String): List<String> {
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

internal fun parseDeclaredFiles(
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

internal fun parseAreaMetadata(manifest: Map<*, *>, slug: String, declaredAreas: List<String>): Map<String, String> {
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

internal fun parseCodeReviewComposition(manifest: Map<*, *>, slug: String): CodeReviewComposition? {
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

internal fun parseCodeReviewBaselineLayer(slug: String, index: Int, raw: Any?): CodeReviewBaselineLayer {
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

internal fun requireStringInMap(slug: String, map: Map<*, *>, fieldLabel: String, key: String): String {
  val value = map[key] as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must be a non-empty string.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must be a non-empty string.")
  }
  return value
}

internal fun parseOptionalString(manifest: Map<*, *>, slug: String, key: String): String? = manifest[key]?.let {
  it as? String ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a string when provided.")
}
