package skillbill.error

import kotlin.enums.EnumEntries

interface FailureWireCode {
  val wireValue: String
}

class UnrecognizedFailureWireCodeError(
  val hierarchy: String,
  val rejectedToken: String,
) : ShellContentContractException(
  "Unrecognized failure wire code '$rejectedToken' for hierarchy '$hierarchy'.",
)

fun coarseFailureKindForPhaseOutputWireCode(wireCode: String): FeatureTaskRuntimePhaseOutputFailureKind =
  when (wireCode) {
    "malformed",
    "root_not_object",
    "no_repair_candidate",
    "ambiguous_repair",
    "repair_limit_exceeded",
    "unsupported_repair",
    "duplicate_key",
    -> FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED
    "schema_invalid",
    "phase_id_mismatch",
    "semantic_invalid",
    "multiple_output_candidates",
    -> FeatureTaskRuntimePhaseOutputFailureKind.SCHEMA_INVALID
    else -> throw UnrecognizedFailureWireCodeError("FeatureTaskRuntimePhaseOutputFailureCode", wireCode)
  }

fun <E> Array<E>.failureWireByValue(value: String, hierarchy: String): E where E : Enum<E>, E : FailureWireCode =
  firstOrNull { it.wireValue == value }
    ?: throw UnrecognizedFailureWireCodeError(hierarchy, value)

fun <E> EnumEntries<E>.failureWireByValue(value: String, hierarchy: String): E where E : Enum<E>, E : FailureWireCode =
  firstOrNull { it.wireValue == value }
    ?: throw UnrecognizedFailureWireCodeError(hierarchy, value)

fun <E> Array<E>.failureWireByValueOrNull(value: String?): E? where E : Enum<E>, E : FailureWireCode =
  value?.let { token -> firstOrNull { it.wireValue == token } }
