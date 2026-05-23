@file:Suppress("TooGenericExceptionCaught")

package skillbill.workflow

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidDecompositionManifestSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val decompositionManifestLog: Logger =
  Logger.getLogger("skillbill.workflow.DecompositionManifestSchemaValidator")

object DecompositionManifestSchemaValidator {
  private val schema: JsonSchema by lazy { loadDecompositionManifestSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(manifest: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(manifest)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isEmpty()) {
      DecompositionManifestCoherenceValidator.validate(manifest, sourceLabel)
      return
    }
    decompositionManifestLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = sourceLabel,
      reason = formatValidationReason(errors.sortedWith(violationOrdering), instance),
    )
  }

  fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?> {
    val node = yamlMapper.readTree(yamlText)
    val parsed = mapper.convertValue(node, mapType)
    validate(parsed, sourceLabel)
    return parsed
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical decomposition manifest schema identity mismatch: loaded '\$id' is '$loadedId' but " +
          "expected '${DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the " +
          "schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != DECOMPOSITION_MANIFEST_CONTRACT_VERSION) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical decomposition manifest schema contract_version.const mismatch: loaded '$loadedConst' " +
          "but the runtime expects '$DECOMPOSITION_MANIFEST_CONTRACT_VERSION'. The schema on the classpath is " +
          "out of date relative to the running runtime-domain.",
      )
    }
  }

  private fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    val parts = errors.sortedWith(violationOrdering).take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = decompositionManifestDottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractDecompositionManifestOffendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Decomposition manifest failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = decompositionManifestDottedFieldPath(instanceLocation).ifBlank { "<root>" }
    val offendingValue = extractDecompositionManifestOffendingValue(instance, instanceLocation)
    return buildString {
      append(fieldPath)
      append(": ")
      append(firstError.message)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = decompositionManifestDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractDecompositionManifestOffendingValue(instance, otherLocation)
        append(" | ")
        append(otherPath)
        append(": ")
        append(other.message)
        if (otherValue.isNotBlank()) {
          append(" — offending value: ")
          append(otherValue)
        }
      }
    }
  }

  private val violationOrdering: Comparator<ValidationMessage> = compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )
}

internal const val DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE: String =
  DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE

internal const val DECOMPOSITION_MANIFEST_SCHEMA_REPO_RELATIVE_PATH: String =
  DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH

private fun loadDecompositionManifestSchema(): JsonSchema {
  try {
    val yamlText = readDecompositionManifestSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    DecompositionManifestSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (error: Throwable) {
    decompositionManifestLog.log(
      Level.SEVERE,
      "Failed to load canonical decomposition manifest schema: " +
        "classpath='$DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$DECOMPOSITION_MANIFEST_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readDecompositionManifestSchemaText(): String {
  DecompositionManifestSchemaValidator::class.java.classLoader
    .getResourceAsStream(DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForDecompositionManifestSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidDecompositionManifestSchemaError(
    sourceLabel = DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical decomposition manifest schema is missing. Expected to find it on the JVM classpath at " +
      "'$DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$DECOMPOSITION_MANIFEST_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

fun decompositionManifestDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

fun extractDecompositionManifestOffendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = decompositionManifestDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> {
        node = node.path(rawSegment.toInt())
      }
      else -> {
        node = node.path(rawSegment)
      }
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

private fun walkForDecompositionManifestSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(DECOMPOSITION_MANIFEST_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
