package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimePhaseOutputStructuralRepair
import skillbill.error.FeatureTaskRuntimePhaseOutputStructuralRepairSource
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.FailureWireCode
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.failureWireByValue

/** Stable contract version for the typed phase-output validation result. */
const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION: String =
  FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_CONTRACT_VERSION

/** The syntax family used by the strict parser. */
enum class FeatureTaskRuntimePhaseOutputFormat(val wireValue: String) {
  JSON("json"),
  YAML("yaml"),

  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputFormat = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = "<wire>",
        reason = "Unrecognized phase-output format wire value '$value'.",
        payloadFreeReason = "Unrecognized phase-output format wire value.",
      )
  }
}

/** The only syntax edits the bounded structural-repair engine may publish. */
enum class FeatureTaskRuntimePhaseOutputRepairOperation(val wireValue: String) {
  REMOVE_EXTRA_CLOSING_DELIMITER("remove_extra_closing_delimiter"),
  ADD_MISSING_CLOSING_DELIMITER("add_missing_closing_delimiter"),
  DEDUPLICATE_KEYS("deduplicate_keys"),
  RESTORE_EXPECTED_SHAPE("restore_expected_shape"),

  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputRepairOperation =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = "<wire>",
          reason = "Unrecognized phase-output repair-operation wire value '$value'.",
          payloadFreeReason = "Unrecognized phase-output repair-operation wire value.",
        )
  }
}

/** Stable, payload-free rejection codes for callers and retry policy. */
enum class FeatureTaskRuntimePhaseOutputFailureCode(
  override val wireValue: String,
) : FailureWireCode {
  MALFORMED("malformed"),
  ROOT_NOT_OBJECT("root_not_object"),
  DUPLICATE_KEY("duplicate_key"),
  NO_REPAIR_CANDIDATE("no_repair_candidate"),
  AMBIGUOUS_REPAIR("ambiguous_repair"),
  REPAIR_LIMIT_EXCEEDED("repair_limit_exceeded"),
  UNSUPPORTED_REPAIR("unsupported_repair"),
  SCHEMA_INVALID("schema_invalid"),
  PHASE_ID_MISMATCH("phase_id_mismatch"),
  SEMANTIC_INVALID("semantic_invalid"),
  MULTIPLE_OUTPUT_CANDIDATES("multiple_output_candidates"),
  ;

  val coarseFailureKind: FeatureTaskRuntimePhaseOutputFailureKind
    get() = when (this) {
      MALFORMED,
      ROOT_NOT_OBJECT,
      NO_REPAIR_CANDIDATE,
      AMBIGUOUS_REPAIR,
      REPAIR_LIMIT_EXCEEDED,
      UNSUPPORTED_REPAIR,
      DUPLICATE_KEY,
      -> FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED
      SCHEMA_INVALID,
      PHASE_ID_MISMATCH,
      SEMANTIC_INVALID,
      MULTIPLE_OUTPUT_CANDIDATES,
      -> FeatureTaskRuntimePhaseOutputFailureKind.SCHEMA_INVALID
    }

  companion object {
    private const val HIERARCHY = "FeatureTaskRuntimePhaseOutputFailureCode"

    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputFailureCode =
      entries.failureWireByValue(value, HIERARCHY)
  }
}

/** Payload-free source position for parser/repair diagnostics. */
data class FeatureTaskRuntimePhaseOutputSourceLocation(
  val sourceLabel: String,
  val offset: Int,
  val line: Int,
  val column: Int,
) {
  init {
    require(sourceLabel.isNotBlank()) { "Phase-output sourceLabel must be non-blank." }
    require(offset >= 0) { "Phase-output source offset must be non-negative." }
    require(line >= 1) { "Phase-output source line must be >= 1." }
    require(column >= 1) { "Phase-output source column must be >= 1." }
  }
}

/** Evidence for a syntax-only repair; it deliberately contains no payload text. */
data class FeatureTaskRuntimePhaseOutputRepairEvidence(
  val contractVersion: String = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION,
  val validatorVersion: String = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val originalDigest: String,
  val repairedDigest: String,
  val operation: FeatureTaskRuntimePhaseOutputRepairOperation,
  val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation,
) {
  init {
    require(contractVersion == FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION) {
      "Phase-output repair evidence has unsupported contract version '$contractVersion'."
    }
    require(validatorVersion == FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION) {
      "Phase-output repair evidence has unsupported validator version '$validatorVersion'."
    }
    require(originalDigest.matches(SHA256_HEX)) {
      "Phase-output repair evidence originalDigest must be lowercase SHA-256."
    }
    require(repairedDigest.matches(SHA256_HEX)) {
      "Phase-output repair evidence repairedDigest must be lowercase SHA-256."
    }
    require(originalDigest != repairedDigest) {
      "Phase-output repair evidence must describe a changed payload."
    }
  }

  companion object {
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    @OpenBoundaryMap("Typed phase-output repair evidence decoded from a private workflow artifact")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseOutputRepairEvidence {
      val expectedFields = setOf(
        "contract_version",
        "validator_version",
        "format",
        "original_digest",
        "repaired_digest",
        "operation",
        "source_location",
      )
      if (raw.keys != expectedFields) {
        throw phaseOutputRepairEvidenceSchemaError(
          "Phase-output repair evidence contains unsupported or missing fields.",
        )
      }
      val location = raw["source_location"] as? Map<*, *>
        ?: throw phaseOutputRepairEvidenceSchemaError(
          "Phase-output repair evidence source_location must be an object.",
        )
      if (location.keys != setOf("source_label", "offset", "line", "column")) {
        throw phaseOutputRepairEvidenceSchemaError(
          "Phase-output repair evidence source_location contains unsupported fields.",
        )
      }
      return FeatureTaskRuntimePhaseOutputRepairEvidence(
        contractVersion = raw.requireRepairEvidenceString("contract_version"),
        validatorVersion = raw.requireRepairEvidenceString("validator_version"),
        format = FeatureTaskRuntimePhaseOutputFormat.fromWire(raw.requireRepairEvidenceString("format")),
        originalDigest = raw.requireRepairEvidenceString("original_digest"),
        repairedDigest = raw.requireRepairEvidenceString("repaired_digest"),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.fromWire(raw.requireRepairEvidenceString("operation")),
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation(
          sourceLabel = location.requireRepairEvidenceString("source_label"),
          offset = location.requireRepairEvidenceInt("offset"),
          line = location.requireRepairEvidenceInt("line"),
          column = location.requireRepairEvidenceInt("column"),
        ),
      )
    }
  }

  @OpenBoundaryMap("Typed phase-output repair evidence at the private workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to contractVersion,
    "validator_version" to validatorVersion,
    "format" to format.wireValue,
    "original_digest" to originalDigest,
    "repaired_digest" to repairedDigest,
    "operation" to operation.wireValue,
    "source_location" to linkedMapOf(
      "source_label" to sourceLocation.sourceLabel,
      "offset" to sourceLocation.offset,
      "line" to sourceLocation.line,
      "column" to sourceLocation.column,
    ),
  )
}

private fun phaseOutputRepairEvidenceSchemaError(reason: String): Nothing =
  throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
    sourceLabel = "repair_evidence",
    reason = reason,
    payloadFreeReason = reason,
  )

private fun Map<*, *>.requireRepairEvidenceString(field: String): String = this[field] as? String
  ?: phaseOutputRepairEvidenceSchemaError("Phase-output repair evidence field '$field' must be a string.")

private fun Map<*, *>.requireRepairEvidenceInt(field: String): Int = when (val value = this[field]) {
  is Int -> value
  is Number -> value.toInt().takeIf { value.toDouble() == it.toDouble() }
  else -> null // untrusted durable JSON value shape: non-integer primitives fail the field below
} ?: phaseOutputRepairEvidenceSchemaError("Phase-output repair evidence field '$field' must be an integer.")

/**
 * Typed result at the phase-output validation boundary. The normalized envelope is
 * the existing schema-validated projection; no parser node or rejected payload is
 * allowed to cross into this contract.
 */
sealed interface FeatureTaskRuntimePhaseOutputValidationResult {
  val contractVersion: String
    get() = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput?

  data class AcceptedUnchanged(
    override val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ) : FeatureTaskRuntimePhaseOutputValidationResult

  data class AcceptedAfterRepair(
    override val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence,
  ) : FeatureTaskRuntimePhaseOutputValidationResult

  data class Rejected(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
    val diagnosticReason: String = reason,
    val payloadFreeReason: String? = reason,
    val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
    /**
     * Payload-free digest/location evidence from a prior successful delimiter-only structural repair
     * on this capture. Present when syntax repair accepted the document and the phase schema later
     * rejected it; absent when no structural repair ran. Never carries response body text.
     */
    val structuralRepairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  ) : FeatureTaskRuntimePhaseOutputValidationResult {
    override val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null
  }
}

data class AcceptedFeatureTaskRuntimePhaseOutput(
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
)

fun FeatureTaskRuntimePhaseOutputValidationResult.requireAcceptedOutput(
  sourceLabel: String,
): AcceptedFeatureTaskRuntimePhaseOutput = when (this) {
  is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged ->
    AcceptedFeatureTaskRuntimePhaseOutput(normalizedOutput, null)
  is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair ->
    AcceptedFeatureTaskRuntimePhaseOutput(normalizedOutput, evidence)
  is FeatureTaskRuntimePhaseOutputValidationResult.Rejected -> {
    requireAccepted(sourceLabel)
    error("Rejected phase-output validation unexpectedly returned an accepted payload.")
  }
}

/**
 * Converts the new result into the established exception at legacy throwing
 * seams. The reason supplied here is already payload-free.
 */
fun FeatureTaskRuntimePhaseOutputValidationResult.requireAccepted(
  sourceLabel: String,
): NormalizedFeatureTaskRuntimePhaseOutput = when (this) {
  is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged -> normalizedOutput
  is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair -> normalizedOutput
  is FeatureTaskRuntimePhaseOutputValidationResult.Rejected -> {
    val evidence = structuralRepairEvidence
    throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
      sourceLabel = sourceLabel,
      reason = diagnosticReason,
      payloadFreeReason = payloadFreeReason,
      failureCode = code.wireValue,
      structuralRepair = evidence?.let {
        FeatureTaskRuntimePhaseOutputStructuralRepair(
          originalDigest = it.originalDigest,
          repairedDigest = it.repairedDigest,
          format = it.format.wireValue,
          operation = it.operation.wireValue,
          source = FeatureTaskRuntimePhaseOutputStructuralRepairSource(
            label = it.sourceLocation.sourceLabel,
            offset = it.sourceLocation.offset,
            line = it.sourceLocation.line,
            column = it.sourceLocation.column,
          ),
        )
      },
    )
  }
}
