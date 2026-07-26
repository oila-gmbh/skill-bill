@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.error.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.error.InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError
import java.nio.file.Files
import java.nio.file.Path

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

private fun validateAgainst(
  payload: Map<String, Any?>,
  repoPath: String,
  classpathResource: String,
  expectedId: String,
  error: (String) -> RuntimeException,
) {
  try {
    val document = YAMLMapper().readTree(readContract(classpathResource, repoPath))
    require(document.path("\$id").asText() == expectedId) { "schema identity mismatch" }
    val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(ObjectMapper().writeValueAsString(document))
    val failures = schema.validate(ObjectMapper().valueToTree(payload))
    if (failures.isNotEmpty()) {
      throw error(failures.sortedBy { it.instanceLocation.toString() }.take(3).joinToString(" | ") { it.message })
    }
  } catch (failure: RuntimeException) {
    throw failure
  } catch (failure: Exception) {
    throw error(failure.message ?: failure::class.simpleName.orEmpty())
  }
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
  throw IllegalStateException("Canonical runtime contract is missing at '$classpathResource' or '$repoPath'.")
}
