@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.error.InvalidFeatureTaskRuntimeBuildReceiptSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.error.InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError
import skillbill.error.InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_REPORTED_SCHEMA_FAILURES = 3

object FeatureTaskRuntimePhaseHandoffSchemaValidator {
  fun validate(payload: Map<String, Any?>, sourceLabel: String) = validateAgainst(
    payload,
    FeatureTaskRuntimePhaseHandoffSchemaPaths.REPO_RELATIVE_PATH,
    FeatureTaskRuntimePhaseHandoffSchemaPaths.CLASSPATH_RESOURCE,
    FeatureTaskRuntimePhaseHandoffSchemaPaths.EXPECTED_SCHEMA_ID,
  ) { reason -> InvalidFeatureTaskRuntimePhaseHandoffSchemaError(sourceLabel, reason) }
}

object FeatureTaskRuntimePersistenceSchemaValidator {
  fun validate(payload: Map<String, Any?>, sourceLabel: String) = validateAgainst(
    payload,
    FeatureTaskRuntimePersistenceSchemaPaths.REPO_RELATIVE_PATH,
    FeatureTaskRuntimePersistenceSchemaPaths.CLASSPATH_RESOURCE,
    FeatureTaskRuntimePersistenceSchemaPaths.EXPECTED_SCHEMA_ID,
  ) { reason -> InvalidFeatureTaskRuntimePersistenceSchemaError(sourceLabel, reason) }
}

object FeatureTaskRuntimeProjectionMeasurementSchemaValidator {
  fun validate(payload: Map<String, Any?>, sourceLabel: String) = validateAgainst(
    payload,
    FeatureTaskRuntimeProjectionMeasurementSchemaPaths.REPO_RELATIVE_PATH,
    FeatureTaskRuntimeProjectionMeasurementSchemaPaths.CLASSPATH_RESOURCE,
    FeatureTaskRuntimeProjectionMeasurementSchemaPaths.EXPECTED_SCHEMA_ID,
  ) { reason -> InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError(sourceLabel, reason) }
}

object FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator {
  fun validate(payload: Map<String, Any?>, sourceLabel: String) = validateAgainst(
    payload,
    FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.REPO_RELATIVE_PATH,
    FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE,
    FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.EXPECTED_SCHEMA_ID,
  ) { reason -> InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError(sourceLabel, reason) }
}

object FeatureTaskRuntimeBuildReceiptSchemaValidator {
  private val schema: JsonSchema by lazy { loadBuildReceiptSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(payload: Map<String, Any?>, sourceLabel: String) {
    val instance = mapper.valueToTree<JsonNode>(payload)
    val failures = schema.validate(instance)
    if (failures.isNotEmpty()) {
      val sorted = failures.sortedBy { it.instanceLocation.toString() }
      val reasons = formatBuildReceiptViolationReasons(sorted.take(MAX_REPORTED_SCHEMA_FAILURES), instance)
      throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
        sourceLabel = sourceLabel,
        reason = reasons.valueBearing,
        payloadFreeReason = reasons.payloadFree,
      )
    }
  }
}

private fun loadBuildReceiptSchema(): JsonSchema {
  val yamlNode = YAMLMapper().readTree(readBuildReceiptSchemaText())
  assertBuildReceiptSchemaIdentity(yamlNode)
  return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(yamlNode), LOCALE_STABLE_SCHEMA_CONFIG)
}

private fun assertBuildReceiptSchemaIdentity(yamlNode: JsonNode) {
  val loadedId = yamlNode.path("\$id").asText("")
  require(loadedId == FeatureTaskRuntimeBuildReceiptSchemaPaths.EXPECTED_SCHEMA_ID) {
    "Canonical feature-task-runtime build receipt schema identity mismatch: loaded '$loadedId' but expected " +
      "'${FeatureTaskRuntimeBuildReceiptSchemaPaths.EXPECTED_SCHEMA_ID}'."
  }
  val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
  require(loadedConst == FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION) {
    "Canonical feature-task-runtime build receipt schema contract_version.const mismatch: loaded " +
      "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION'."
  }
}

private fun readBuildReceiptSchemaText(): String {
  FeatureTaskRuntimeBuildReceiptSchemaValidator::class.java.classLoader
    .getResourceAsStream(FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }
  var current: Path? = Path.of("").toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(FeatureTaskRuntimeBuildReceiptSchemaPaths.REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return Files.readString(candidate)
    current = current.parent
  }
  throw IllegalStateException(
    "Canonical feature-task-runtime build receipt schema is missing. Expected it on the JVM classpath at " +
      "'${FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE}' or on disk under " +
      "'${FeatureTaskRuntimeBuildReceiptSchemaPaths.REPO_RELATIVE_PATH}'.",
  )
}

private data class BuildReceiptViolationReasons(val valueBearing: String, val payloadFree: String)

private fun formatBuildReceiptViolationReasons(
  sorted: List<ValidationMessage>,
  instance: JsonNode,
): BuildReceiptViolationReasons {
  val violations = sorted.map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = workflowStateSchemaDottedFieldPath(location).ifBlank { "<root>" }
    val head = "$fieldPath: ${error.message}"
    Pair(head, extractOffendingValueFromInstance(instance, location))
  }
  fun render(includeOffendingValues: Boolean): String =
    violations.joinToString(separator = " | ") { (head, offendingValue) ->
      if (includeOffendingValues && offendingValue.isNotBlank()) {
        "$head — offending value: $offendingValue"
      } else {
        head
      }
    }
  return BuildReceiptViolationReasons(valueBearing = render(true), payloadFree = render(false))
}

private fun validateAgainst(
  payload: Map<String, Any?>,
  repoPath: String,
  classpathResource: String,
  expectedId: String,
  error: (String) -> RuntimeException,
) {
  val failures = mapContractFailures(error) {
    val document = YAMLMapper().readTree(readContract(classpathResource, repoPath))
    require(document.path("\$id").asText() == expectedId) { "schema identity mismatch" }
    val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(ObjectMapper().writeValueAsString(document), LOCALE_STABLE_SCHEMA_CONFIG)
    schema.validate(ObjectMapper().valueToTree(payload))
  }
  if (failures.isNotEmpty()) {
    val reason = failures
      .sortedBy { it.instanceLocation.toString() }
      .take(MAX_REPORTED_SCHEMA_FAILURES)
      .joinToString(" | ") { it.message }
    throw error(reason)
  }
}

private inline fun <T> mapContractFailures(error: (String) -> RuntimeException, block: () -> T): T = try {
  block()
} catch (failure: RuntimeException) {
  throw failure
} catch (failure: Exception) {
  throw error(failure.message ?: failure::class.simpleName.orEmpty())
}

private fun readContract(classpathResource: String, repoPath: String): String {
  FeatureTaskRuntimePhaseHandoffSchemaValidator::class.java.classLoader
    .getResourceAsStream(classpathResource)?.use { return it.readBytes().toString(Charsets.UTF_8) }
  var current: Path? = Path.of("").toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(repoPath)
    if (Files.isRegularFile(candidate)) return Files.readString(candidate)
    current = current.parent
  }
  error("Canonical runtime contract is missing at '$classpathResource' or '$repoPath'.")
}
