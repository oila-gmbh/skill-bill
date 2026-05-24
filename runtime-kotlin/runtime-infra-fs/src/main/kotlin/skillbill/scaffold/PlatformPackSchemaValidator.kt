package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidManifestSchemaError
import java.nio.file.Files
import java.nio.file.Path

/**
 * SKILL-47: validates a parsed `platform.yaml` against the canonical
 * JSON-Schema document at `orchestration/contracts/platform-pack-schema.yaml`.
 *
 * Wraps `com.networknt:json-schema-validator` behind a thin Kotlin interface
 * so the underlying library choice stays local. The schema is loaded ONCE
 * per repo-root (cached) because schema compilation is non-trivial.
 *
 * Coherence rules (cross-field validation) stay in
 * [ShellContentLoader.buildPack]; see `x-coherence-checks` in the schema
 * file for the named list.
 */
internal interface PlatformPackSchemaValidator {
  /**
   * Validates the parsed YAML manifest against the canonical schema. On
   * any violation, throws [InvalidManifestSchemaError] whose message names
   * the offending field path so the failure surface stays loud and useful.
   *
   * SKILL-48 C2: callers MUST hand in an already-shape-checked top-level
   * mapping. The `Map<String, Any?>` signature (vs the prior `Any?`) makes
   * "is it a mapping?" a compile-time concern of the caller (`ShellContentLoader`
   * already enforces this via `requireManifestMap`), so this method no longer
   * carries dead defensive coercion.
   */
  fun validate(parsedYaml: Map<String, Any?>, slug: String)
}

/**
 * Default implementation. Resolves the canonical schema from the JVM
 * classpath first (populated at build time from
 * `orchestration/contracts/platform-pack-schema.yaml`); when running from
 * a tree that does not yet bundle the schema as a resource (early bootstrap),
 * it falls back to walking up from the JVM working directory to find the
 * canonical file on disk. The compiled [JsonSchema] is cached across calls.
 *
 * SKILL-48 C3: the prior `repoRootHint` parameter was never set by any caller
 * and only added a never-used branch to [readSchemaText]. It has been removed;
 * the disk fallback uses `Path.of("")` as its single walk anchor.
 */
internal class CanonicalPlatformPackSchemaValidator : PlatformPackSchemaValidator {
  // Lazy singleton: the schema file is parsed and compiled exactly once
  // per validator instance.
  private val schema: JsonSchema by lazy { loadSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun validate(parsedYaml: Map<String, Any?>, slug: String) {
    val instance: JsonNode = mapper.valueToTree(parsedYaml)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    // F-009 (SKILL-47): `contract_version` value mismatches must surface as
    // the dedicated `ContractVersionMismatchError`, even when discovered
    // here (in `loadPlatformManifest`/`loadPlatformPack` callers alike), so
    // every load path is consistent. Previously the validator silently
    // filtered the `const` failure and deferred to `validatePlatformPack`,
    // which meant `loadPlatformManifest` (no contract gate) accepted any
    // version with no signal. Now we throw the typed error directly the
    // moment the const violation is observed.
    val contractVersionConst = errors.firstOrNull { it.isContractVersionConstMismatch() }
    if (contractVersionConst != null) {
      throw ContractVersionMismatchError(
        buildContractVersionMismatchMessage(slug, instance, contractVersionConst),
      )
    }
    if (errors.isEmpty()) {
      return
    }
    throw InvalidManifestSchemaError(formatValidationMessage(slug, errors, instance))
  }

  private fun ValidationMessage.isContractVersionConstMismatch(): Boolean {
    val dotted = dottedFieldPath(instanceLocation?.toString().orEmpty())
    return dotted == "contract_version" && type == "const"
  }

  /**
   * F-009: builds a typed `ContractVersionMismatchError` message that names BOTH the field
   * (`contract_version`) and the actual offending value so callers / tests can discriminate the
   * cause without parsing free-form schema validator output. Falls back to the validator's raw
   * detail when the actual value cannot be extracted from the parsed instance.
   */
  private fun buildContractVersionMismatchMessage(slug: String, instance: JsonNode, error: ValidationMessage): String {
    val actual = extractOffendingValue(instance, error.instanceLocation?.toString().orEmpty())
    return buildString {
      append("Platform pack '")
      append(slug)
      append("': declares contract_version")
      if (actual.isNotBlank()) {
        append(" '")
        append(actual)
        append("'")
      }
      append(" but the shell expects '")
      append(SHELL_CONTRACT_VERSION)
      append("'.")
    }
  }

  private fun formatValidationMessage(slug: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    // Deterministic ordering by instanceLocation so the loud-fail message is
    // stable across runs (networknt returns a LinkedHashSet but ordering is
    // not part of its public contract).
    val sorted = errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = dottedFieldPath(instanceLocation)
    val detail = firstError.message
    val hint = humanReadableHintFor(firstError, fieldPath)
    val offendingValue = extractOffendingValue(instance, instanceLocation)
    val others = if (errors.size > 1) " (+ ${errors.size - 1} more)" else ""
    return buildString {
      append("Platform pack '")
      append(slug)
      append("': manifest fails schema validation at '")
      append(fieldPath.ifBlank { "<root>" })
      append("': ")
      append(detail)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      if (hint.isNotBlank()) {
        append(" — ")
        append(hint)
      }
      append(others)
    }
  }

  // Best-effort extraction of the failing value from the parsed instance so the
  // loud-fail message names the offending datum (e.g. 'laravel') instead of just
  // the field path. Walks the JSON tree using the dotted/indexed path; returns
  // an empty string for non-scalar nodes (the surrounding message already
  // describes the structural problem).
  private fun extractOffendingValue(instance: JsonNode, instanceLocation: String): String {
    val dotted = dottedFieldPath(instanceLocation)
    if (dotted.isBlank()) return ""
    var node: JsonNode = instance
    // Path segments can be plain keys or `[<index>]` for arrays.
    dotted.split('.').forEach { rawSegment ->
      if (rawSegment.isBlank()) return@forEach
      val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
      if (arrayMatch != null) {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      } else {
        node = node.path(rawSegment)
      }
    }
    return when {
      node.isMissingNode -> ""
      node.isValueNode -> node.asText()
      else -> ""
    }
  }

  // Augment the raw schema message with a domain-specific hint. Keeps loud-fail
  // messages aligned with the human-readable rule (e.g. content.md path suffix)
  // documented in the schema, instead of leaking raw regex like `(^|/)content\.md$`.
  private fun humanReadableHintFor(error: ValidationMessage, fieldPath: String): String {
    val keyword = error.type.orEmpty()
    val isPointerName = fieldPath.startsWith("pointers") && fieldPath.endsWith(".name")
    val isPointerTarget = fieldPath.startsWith("pointers") && fieldPath.endsWith(".target")
    return when {
      keyword == "pattern" && fieldPath.startsWith("declared_files") ->
        "schema requires this field to point directly at 'content.md'."
      keyword == "pattern" && fieldPath == "declared_quality_check_file" ->
        "schema requires this field to point directly at 'content.md'."
      keyword == "pattern" && isPointerName ->
        "schema requires pointer 'name' to be a bare '.md' filename (no path separators)."
      keyword == "minLength" && isPointerTarget ->
        "schema requires a non-empty 'target' on every pointer entry."
      keyword == "not" && isPointerName ->
        "schema forbids '..' in pointer 'name'."
      else -> ""
    }
  }

  // networknt 1.5.x reports `instanceLocation` in JSONPath form like
  // `$.declared_files.baseline`. JSON-Pointer form (`/declared_files/baseline`)
  // is also accepted as a fallback for older builds. The resulting dotted
  // form is human-readable and stable across library upgrades. Keys are
  // preserved verbatim even when they contain `/` (e.g. pointer keys like
  // `code-review/bill-fixturepack-code-review`).
  private fun dottedFieldPath(instanceLocation: String): String = when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    // Pure JSON-Pointer form: `/a/b/0` -> `a.b.0`. Only used as a fallback
    // because networknt 1.5.x emits JSONPath by default.
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }
}

/**
 * SKILL-47: single source of truth for where the canonical platform-pack
 * schema lives. Every runtime caller — including the desktop browser, tests,
 * and the Gradle copy task documented below — should reference these
 * constants so the canonical path appears exactly once in the codebase.
 *
 * The Gradle Kotlin DSL cannot import runtime constants directly. The copy
 * task in `runtime-core/build.gradle.kts` MUST mirror these values; if the
 * paths ever drift, `PlatformPackSchemaValidator.loadSchema` will fail loudly
 * at runtime (the classpath resource will not be found at
 * [CLASSPATH_RESOURCE]) and any test that resolves [REPO_RELATIVE_PATH] will
 * report the missing file.
 */
object PlatformPackSchemaPaths {
  /** Repo-relative path to the canonical schema YAML on disk. */
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/platform-pack-schema.yaml"

  /** Classpath resource path where runtime-core bundles the schema for runtime loads. */
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/platform-pack-schema.yaml"

  /**
   * SKILL-48 C7: expected value of the canonical schema's `$id`. The validator asserts
   * that the schema document loaded from the classpath / disk matches this value, so a
   * stale or shadowed copy fails loudly with [skillbill.error.InvalidManifestSchemaError]
   * instead of silently validating against the wrong contract.
   */
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/platform-pack-schema.yaml"
}

internal const val PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE: String =
  PlatformPackSchemaPaths.CLASSPATH_RESOURCE

internal const val PLATFORM_PACK_SCHEMA_REPO_RELATIVE_PATH: String =
  PlatformPackSchemaPaths.REPO_RELATIVE_PATH

/**
 * Loads the canonical schema YAML text from the classpath first; failing
 * that, walks up from the JVM working directory to find the on-disk file.
 * Re-emits as JSON before handing to networknt so the validator gets a
 * predictable JSON tree regardless of how the schema is authored.
 *
 * SKILL-48 C7: after compiling the schema, asserts the loaded document's
 * `$id` and `properties.contract_version.const` match the runtime's expected
 * values. This protects against a stale schema being shadowed onto the
 * classpath by a sibling jar; we'd rather loud-fail than silently validate
 * against an unexpected contract.
 */
private fun loadSchema(): JsonSchema {
  val yamlText = readSchemaText()
  val yamlNode = YAMLMapper().readTree(yamlText)
  assertSchemaIdentity(yamlNode)
  val jsonText = ObjectMapper().writeValueAsString(yamlNode)
  val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
  return factory.getSchema(jsonText)
}

/**
 * SKILL-48 Subtask 3: returns the set of top-level property names whose
 * `x-runtime-anchored: true` marker designates them as fields the runtime
 * consumes by name (read by `ShellContentLoader.buildPack` into typed
 * `PlatformManifest` fields).
 *
 * Used by two callers:
 *
 *  - `ShellContentLoader.buildPack` to compute `PlatformManifest.customFields`
 *    by filtering out anchored keys from the parsed YAML.
 *  - `PlatformPackSchemaAnchoredBijectionTest` to assert schema↔Kotlin parity
 *    in both directions.
 *
 * Loud-fails (via [InvalidManifestSchemaError]) if the canonical schema cannot
 * be loaded, mirroring [loadSchema]'s behavior. The first access triggers the
 * computation (and any loud-fail); subsequent accesses return the cached set
 * because the bundled schema is immutable at runtime.
 */
internal fun anchoredTopLevelFieldNames(): Set<String> = ANCHORED_TOP_LEVEL_FIELD_NAMES

private val ANCHORED_TOP_LEVEL_FIELD_NAMES: Set<String> by lazy {
  val yamlText = readSchemaText()
  val yamlNode: JsonNode = YAMLMapper().readTree(yamlText)
  val properties = yamlNode.path("properties")
  if (properties.isMissingNode || !properties.isObject) {
    throw InvalidManifestSchemaError(
      "Canonical platform-pack schema is missing a top-level 'properties' object; cannot derive " +
        "the anchored top-level field set.",
    )
  }
  val anchored = linkedSetOf<String>()
  properties.fields().forEach { (name, definition) ->
    if (definition.path("x-runtime-anchored").asBoolean(false)) {
      anchored += name
    }
  }
  anchored
}

// SKILL-48 C7: package-internal so tests can drive the assertion with synthesized YAML nodes
// without round-tripping through the classpath-loaded schema (which is bundled by Gradle and
// always matches at test time).
internal fun assertSchemaIdentity(yamlNode: JsonNode) {
  val loadedId = yamlNode.path("\$id").asText("")
  if (loadedId != PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID) {
    throw InvalidManifestSchemaError(
      "Canonical platform-pack schema identity mismatch: loaded '\$id' is '$loadedId' but expected " +
        "'${PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the schema is on " +
        "the classpath.",
    )
  }
  val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
  if (loadedConst != SHELL_CONTRACT_VERSION) {
    throw InvalidManifestSchemaError(
      "Canonical platform-pack schema contract_version.const mismatch: loaded '$loadedConst' but the " +
        "shell expects '$SHELL_CONTRACT_VERSION'. The schema on the classpath is out of date relative to " +
        "the running runtime-core.",
    )
  }
}

private fun readSchemaText(): String {
  CanonicalPlatformPackSchemaValidator::class.java.classLoader
    .getResourceAsStream(PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidManifestSchemaError(
    "Canonical platform-pack schema is missing. Expected to find it on the JVM classpath at " +
      "'$PLATFORM_PACK_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$PLATFORM_PACK_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private fun walkForSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(PLATFORM_PACK_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
