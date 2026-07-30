package skillbill.workflow.taskruntime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import skillbill.error.InvalidFeatureTaskRuntimeConvergenceStateSchemaError
import skillbill.workflow.taskruntime.model.CONVERGENCE_STATE_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus

fun interface ConvergenceStateSchemaValidator {
  fun validate(encoded: String, sourceLabel: String)
}

object ConvergenceStateCodec {
  private val json = Json { ignoreUnknownKeys = false }

  fun decodeRecord(
    encoded: String,
    sourceLabel: String,
    schemaValidator: ConvergenceStateSchemaValidator,
  ): ConvergenceRecord = try {
    schemaValidator.validate(encoded, sourceLabel)
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
  } catch (error: SerializationException) {
    invalidRecord(sourceLabel, error)
  } catch (error: IllegalArgumentException) {
    invalidRecord(sourceLabel, error)
  } catch (error: IllegalStateException) {
    invalidRecord(sourceLabel, error)
  }

  fun decodeLegacySource(
    encoded: String,
    sourceLabel: String,
    schemaValidator: ConvergenceStateSchemaValidator,
  ): List<ConvergenceRecord> = try {
    val root = json.parseToJsonElement(encoded) as? JsonObject
      ?: invalid(sourceLabel, "legacy source must be an object")
    if (root.keys != setOf("contract_version", "records")) invalid(sourceLabel, "legacy source fields are invalid")
    if (root.string("contract_version") != CONVERGENCE_STATE_CONTRACT_VERSION) {
      invalid(sourceLabel, "unsupported legacy contract_version")
    }
    val records = root["records"] as? kotlinx.serialization.json.JsonArray
      ?: invalid(sourceLabel, "legacy records must be an array")
    records.mapIndexed { index, element ->
      decodeRecord(element.toString(), "$sourceLabel.records[$index]", schemaValidator)
    }
  } catch (error: InvalidFeatureTaskRuntimeConvergenceStateSchemaError) {
    throw error
  } catch (error: SerializationException) {
    invalidLegacySource(sourceLabel, error)
  } catch (error: IllegalArgumentException) {
    invalidLegacySource(sourceLabel, error)
  } catch (error: IllegalStateException) {
    invalidLegacySource(sourceLabel, error)
  }

  fun encodeLegacySource(records: List<ConvergenceRecord>): String = buildJsonObject {
    put("contract_version", CONVERGENCE_STATE_CONTRACT_VERSION)
    put("records", buildJsonArray { records.forEach { add(encodeRecordObject(it)) } })
  }.toString()

  fun encodeRecord(record: ConvergenceRecord): String = encodeRecordObject(record).toString()

  private fun encodeRecordObject(record: ConvergenceRecord): JsonObject = buildJsonObject {
    put("contract_version", CONVERGENCE_STATE_CONTRACT_VERSION)
    put("record_id", record.recordId)
    put("workflow_id", record.provenance.workflowId)
    put("kind", record.kind.name.lowercase())
    put("generation", record.provenance.generation)
    put("logical_id", record.logicalId)
    record.parentLogicalId?.let { put("parent_logical_id", it) }
    put("phase_id", record.provenance.phaseId)
    record.provenance.attempt?.let { put("attempt", it) }
    record.provenance.reviewPass?.let { put("review_pass", it) }
    put("status", record.status.name.lowercase())
    record.classification?.let { put("classification", it) }
    record.summary?.let { put("summary", it) }
    record.path?.let { put("path", it) }
    put("evidence_digest", record.evidenceDigest)
    record.evidenceRef?.let { put("evidence_ref", it) }
    put("created_at", record.createdAt)
  }
}

private fun invalid(source: String, reason: String): Nothing =
  throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(source, reason)

private fun invalidRecord(source: String, error: RuntimeException): Nothing =
  throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(
    source,
    "record violates the convergence-state contract",
    error,
  )

private fun invalidLegacySource(source: String, error: RuntimeException): Nothing =
  throw InvalidFeatureTaskRuntimeConvergenceStateSchemaError(
    source,
    "legacy source violates the convergence-state contract",
    error,
  )

private fun JsonObject.string(name: String): String = get(name)?.jsonPrimitive?.contentOrNull ?: error("missing $name")

private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int = get(name)?.jsonPrimitive?.intOrNull ?: error("missing $name")

private fun JsonObject.optionalInt(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull
