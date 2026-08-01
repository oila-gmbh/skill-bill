package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError

/** Stable contract version for the typed phase-output validation result. */
const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION: String =
  FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_CONTRACT_VERSION

/** The syntax family used by the strict parser. */
enum class FeatureTaskRuntimePhaseOutputFormat(val wireValue: String) {
  JSON("json"),
  YAML("yaml"),
}

/** The only syntax edits the bounded structural-repair engine may publish. */
enum class FeatureTaskRuntimePhaseOutputRepairOperation(val wireValue: String) {
  REMOVE_EXTRA_CLOSING_DELIMITER("remove_extra_closing_delimiter"),
  ADD_MISSING_CLOSING_DELIMITER("add_missing_closing_delimiter"),
}

/** Stable, payload-free rejection codes for callers and retry policy. */
enum class FeatureTaskRuntimePhaseOutputFailureCode(val wireValue: String) {
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

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputFailureCode =
      entries.firstOrNull { it.wireValue == value } ?: SCHEMA_INVALID
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

  private companion object {
    val SHA256_HEX = Regex("[0-9a-f]{64}")
  }
}

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
    val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ) : FeatureTaskRuntimePhaseOutputValidationResult {
    override val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null
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
  is FeatureTaskRuntimePhaseOutputValidationResult.Rejected -> throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
    sourceLabel = sourceLabel,
    reason = reason,
    payloadFreeReason = reason,
    failureKind = when (code) {
      FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
      FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
      FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE,
      FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
      FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED,
      FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR,
      FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY,
      -> FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED
      else -> FeatureTaskRuntimePhaseOutputFailureKind.SCHEMA_INVALID
    },
    failureCode = code.wireValue,
  )
}
