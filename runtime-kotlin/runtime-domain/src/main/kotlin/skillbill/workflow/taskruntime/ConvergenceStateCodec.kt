package skillbill.workflow.taskruntime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_SCHEMA_RESOURCE
import skillbill.error.InvalidFeatureTaskRuntimeConvergenceStateSchemaError

object ConvergenceStateCodec {
  private val json = Json { ignoreUnknownKeys = false }

  fun decodeRecord(encoded: String, sourceLabel: String): ConvergenceRecord = try {
    requireBundledContract(sourceLabel)
    val value = json.parseToJsonElement(encoded) as? JsonObject
      ?: invalid(sourceLabel, "record must be an object")
    val allowed = setOf(
      "contract_version", "record_id", "workflow_id", "kind", "generation", "logical_id",
      "parent_logical_id", "phase_id", "attempt", "review_pass", "status", "classification",
      "summary", "path", "evidence_digest", "evidence_ref", "created_at",
    )
    val unknown = value.keys - allowed
    if (unknown.isNotEmpty()) invalid(sourceLabel, "unknown field '${unknown.first()}'")
    if (value.string("contract_version") != CONVERGENCE_STATE_CONTRACT_VERSION) {
      invalid(sourceLabel, "unsupported contract_version")
    }
    ConvergenceRecord(
      recordId = value.string("record_id"),
      logicalId = value.string("logical_id"),
      kind = ConvergenceRecordKind.valueOf(value.string("kind").uppercase()),
      provenance = ConvergenceProvenance(
        workflowId = value.string("workflow_id"),
        generation = value.int("generation"),
        phaseId = value.string("phase_id"),
        attempt = value.optionalInt("attempt"),
        reviewPass = value.optionalInt("review_pass"),
      ),
      evidenceDigest = value.string("evidence_digest"),
      createdAt = value.string("created_at"),
      status = ConvergenceStatus.valueOf(value.string("status").uppercase()),
      classification = value.optionalString("classification"),
      summary = value.optionalString("summary"),
      parentLogicalId = value.optionalString("parent_logical_id"),
      path = value.optionalString("path"),
      evidenceRef = value.optionalString("evidence_ref"),
    )
  } catch (error: InvalidFeatureTaskRuntimeConvergenceStateSchemaError) {
    throw error
  } catch (error: Exception) {
    throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(
      sourceLabel,
      "record violates the convergence-state contract",
      error,
    )
  }

  fun decodeLegacySource(encoded: String, sourceLabel: String): List<ConvergenceRecord> = try {
    val root = json.parseToJsonElement(encoded) as? JsonObject
      ?: invalid(sourceLabel, "legacy source must be an object")
    if (root.keys != setOf("contract_version", "records")) invalid(sourceLabel, "legacy source fields are invalid")
    if (root.string("contract_version") != CONVERGENCE_STATE_CONTRACT_VERSION) {
      invalid(sourceLabel, "unsupported legacy contract_version")
    }
    val records = root["records"] as? kotlinx.serialization.json.JsonArray
      ?: invalid(sourceLabel, "legacy records must be an array")
    records.mapIndexed { index, element -> decodeRecord(element.toString(), "$sourceLabel.records[$index]") }
  } catch (error: InvalidFeatureTaskRuntimeConvergenceStateSchemaError) {
    throw error
  } catch (error: Exception) {
    throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(
      sourceLabel,
      "legacy source violates the convergence-state contract",
      error,
    )
  }

  private fun invalid(source: String, reason: String): Nothing =
    throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(source, reason)

  private fun requireBundledContract(source: String) {
    val schema = ConvergenceStateCodec::class.java.classLoader
      .getResourceAsStream(FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_SCHEMA_RESOURCE)
      ?.bufferedReader()
      ?.use { it.readText() }
      ?: invalid(source, "bundled convergence-state schema is missing")
    if ("const: \"$CONVERGENCE_STATE_CONTRACT_VERSION\"" !in schema) {
      invalid(source, "bundled convergence-state schema version is incompatible")
    }
  }

  private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull ?: error("missing $name")

  private fun JsonObject.optionalString(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

  private fun JsonObject.int(name: String): Int =
    get(name)?.jsonPrimitive?.intOrNull ?: error("missing $name")

  private fun JsonObject.optionalInt(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull
}
