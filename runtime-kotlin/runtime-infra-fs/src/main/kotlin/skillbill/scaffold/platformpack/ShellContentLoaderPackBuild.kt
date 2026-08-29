@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount", "TooManyFunctions")

package skillbill.scaffold.platformpack

import org.yaml.snakeyaml.Yaml
import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidFallbackCapabilityError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidValidationGateDeclarationError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.FeatureAddonUsage
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.runtime.CONTENT_BODY_FILENAME
import skillbill.scaffold.runtime.SHELL_CONTRACT_VERSION
import skillbill.scaffold.validation.parseSkillFrontmatter
import skillbill.scaffold.validation.validateAuthoredContent
import skillbill.scaffold.validation.validateReviewSkillStructure
import skillbill.scaffold.validation.validateSkillMdShape
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.InvalidPathException
import skillbill.scaffold.validation.ReviewSkillStructureValidator

internal fun readManifest(manifestPath: Path, slug: String): Any? = try {
  Yaml().load<Any?>(Files.readString(manifestPath))
} catch (error: Exception) {
  throw InvalidManifestSchemaError(
    "Platform pack '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
    error,
  )
}

internal fun buildPack(
  slug: String,
  packRoot: Path,
  manifestPath: Path,
  raw: Any?,
  enforceContractVersion: Boolean,
): PlatformManifest {
  val manifest = requireManifestMap(slug, manifestPath, raw)
  val typedManifest = validateAgainstCanonicalSchema(slug, manifest, enforceContractVersion)
  validatePlatformSlug(slug, requireStringField(manifest, slug, "platform"))
  val contractVersion = requireStringField(manifest, slug, "contract_version")
  val declaredAreas = parseDeclaredAreas(manifest, slug)
  val routingSignals = parseRoutingSignals(
    manifest,
    slug,
    requirePath = manifest["lane_conditions"] != null,
    fallbackOnly = manifest["fallback_capabilities"] != null,
  )
  val declaredFiles = parseDeclaredFiles(manifest, slug, packRoot, declaredAreas)
  val areaMetadata = parseAreaMetadata(manifest, slug, declaredAreas)
  val laneConditions = parseLaneConditions(manifest, slug, declaredAreas)
  val displayName = parseOptionalString(manifest, slug, "display_name")
  val notes = parseOptionalString(manifest, slug, "notes")
  val declaredQualityCheckFile = parseOptionalPath(manifest, slug, "declared_quality_check_file", packRoot)
  val validationGate = parseValidationGate(manifest, slug)
  val codeReviewComposition = parseCodeReviewComposition(manifest, slug)
  val fallbackCapabilities = parseFallbackCapabilities(manifest, slug)
  val pointers = parsePointers(manifest, slug)
  val addonUsage = parseAddonUsage(
    manifest,
    AddonUsageManifestContext(
      slug = slug,
      packRoot = packRoot,
      pointers = pointers,
      declaredSkillDirs = declaredSkillRelativeDirs(packRoot, declaredFiles, declaredQualityCheckFile),
      declaredAreas = declaredAreas.toSet(),
      strictReviewRouting = laneConditions.isNotEmpty(),
    ),
  )
  val featureAddonUsage = parseFeatureAddonUsage(manifest, slug, packRoot, pointers)
  val customFields = validatedCustomFields(slug, manifestPath, typedManifest)
  return PlatformManifest(
    slug = slug,
    packRoot = packRoot,
    contractVersion = contractVersion,
    routingSignals = routingSignals,
    declaredCodeReviewAreas = declaredAreas,
    declaredFiles = declaredFiles,
    areaMetadata = areaMetadata,
    laneConditions = laneConditions,
    displayName = displayName,
    notes = notes,
    declaredQualityCheckFile = declaredQualityCheckFile,
    validationGate = validationGate,
    codeReviewComposition = codeReviewComposition,
    fallbackCapabilities = fallbackCapabilities,
    pointers = pointers,
    addonUsage = addonUsage,
    featureAddonUsage = featureAddonUsage,
    customFields = customFields,
  )
}

internal fun validatePlatformSlug(slug: String, declaredPlatform: String) {
  if (declaredPlatform != slug) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': manifest 'platform' field is '$declaredPlatform', " +
        "expected '$slug' to match the directory name.",
    )
  }
}

internal fun parseFallbackCapabilities(manifest: Map<*, *>, slug: String): Set<String> {
  val raw = manifest["fallback_capabilities"] ?: return emptySet()
  val values = raw as? List<*> ?: throw InvalidManifestSchemaError(
    "Platform pack '$slug': 'fallback_capabilities' must be a list.",
  )
  return values.mapIndexed { index, value ->
    (value as? String)?.trim()?.takeIf(String::isNotEmpty) ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'fallback_capabilities[$index]' must be a non-blank string.",
    )
  }.toSet()
}

internal const val CODE_REVIEW_FALLBACK_CAPABILITY = "code-review"

internal fun extractCustomFields(manifest: Map<String, Any?>): Map<String, Any?> =
  manifest.filterKeys { it !in anchoredTopLevelFieldNames() }

internal fun validatedCustomFields(slug: String, manifestPath: Path, manifest: Map<String, Any?>): Map<String, Any?> {
  val customFields = extractCustomFields(manifest)
  guardAgainstAnchoredFieldTypos(slug, manifestPath, customFields.keys, anchoredTopLevelFieldNames())
  return customFields
}

internal fun requireManifestMap(slug: String, manifestPath: Path, raw: Any?): Map<*, *> = raw as? Map<*, *>
  ?: throw InvalidManifestSchemaError(
    "Platform pack '$slug': manifest '$manifestPath' must be a YAML mapping at the top level.",
  )

// SKILL-48 A5(b): loud-fail when a top-level custom-field key looks like a typo of an
// anchored field. Iterates `anchoredKeys` in its existing (schema-property) order so the
// first near-match wins deterministically. Exact matches are skipped defensively — by
// construction `customFields` has already filtered them out, but the guard makes the
// intent explicit.
internal fun guardAgainstAnchoredFieldTypos(
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
internal fun levenshtein1(a: String, b: String): Boolean {
  val lengthDelta = a.length - b.length
  if (lengthDelta < -1 || lengthDelta > 1 || a == b) return false
  return if (a.length == b.length) substitutionMatches(a, b) else insertionOrDeletionMatches(a, b)
}

// Substitution case (equal lengths): exactly one position must differ.
internal fun substitutionMatches(a: String, b: String): Boolean {
  val diffs = a.indices.count { a[it] != b[it] }
  return diffs == 1
}

// Insertion/deletion case (lengths differ by 1): align the shorter string
// inside the longer string and allow one skipped character in the longer one.
internal fun insertionOrDeletionMatches(a: String, b: String): Boolean {
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
internal val canonicalSchemaValidator: PlatformPackSchemaValidator by lazy {
  CanonicalPlatformPackSchemaValidator()
}

internal fun validateAgainstCanonicalSchema(
  slug: String,
  manifest: Map<*, *>,
  enforceContractVersion: Boolean,
): Map<String, Any?> {
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
  canonicalSchemaValidator.validate(typedManifest, slug, enforceContractVersion)
  // SKILL-48 Subtask 3: callers reuse the validated typed map to derive `customFields` so
  // we do not re-walk the raw `Map<*, *>` and re-do the key shape check.
  return typedManifest
}
