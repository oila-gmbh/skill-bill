package skillbill.infrastructure.sqlite

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_SCHEMA_RESOURCE
import skillbill.error.InvalidFeatureTaskRuntimeConvergenceStateSchemaError
import skillbill.workflow.taskruntime.ConvergenceStateSchemaValidator

internal fun bundledConvergenceStateSchemaValidator(): ConvergenceStateSchemaValidator {
  val source = FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_SCHEMA_RESOURCE
  val schemaDocument = SQLiteConvergenceStateRepository::class.java.classLoader
    .getResourceAsStream(source)
    ?.use { YAMLMapper().readTree(it) }
    ?: invalidConvergenceState(source, "bundled schema is missing")
  val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaDocument)
  val mapper = ObjectMapper()
  return ConvergenceStateSchemaValidator { encoded, sourceLabel ->
    val document = try {
      mapper.readTree(encoded)
    } catch (error: JsonProcessingException) {
      invalidConvergenceState(sourceLabel, "record is not valid JSON", error)
    }
    val errors = schema.validate(document)
    if (errors.isNotEmpty()) {
      val reason = errors.sortedBy { it.instanceLocation.toString() }.joinToString("; ") { it.message }
      invalidConvergenceState(sourceLabel, reason)
    }
  }
}

private fun invalidConvergenceState(source: String, reason: String, cause: Throwable? = null): Nothing =
  throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(source, reason, cause)
