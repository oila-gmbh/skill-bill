@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
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
