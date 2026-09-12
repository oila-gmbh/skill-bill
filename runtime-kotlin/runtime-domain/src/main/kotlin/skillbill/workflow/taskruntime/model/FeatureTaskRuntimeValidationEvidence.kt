package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.error.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError

private const val MAX_VALIDATION_RESULTS = 50

data class FeatureTaskRuntimeValidationCommandResult(
  val command: String,
  val exitCode: Int,
) {
  init {
    require(command.isNotBlank()) { "Validation command must be non-blank." }
  }

  @OpenBoundaryMap("Runtime-owned validation command result at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    ValidationEvidencePayloadKeys.COMMAND to command,
    ValidationEvidencePayloadKeys.EXIT_CODE to exitCode,
  )
}

data class FeatureTaskRuntimeValidationEvidence(
  val results: List<FeatureTaskRuntimeValidationCommandResult>,
) {
  init {
    require(results.isNotEmpty()) { "Validation evidence must contain at least one result." }
    require(results.size <= MAX_VALIDATION_RESULTS) {
      "Validation evidence cannot contain more than $MAX_VALIDATION_RESULTS results."
    }
  }

  @OpenBoundaryMap("Runtime-owned validation evidence at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
    ValidationEvidencePayloadKeys.RESULTS to results.map(FeatureTaskRuntimeValidationCommandResult::toArtifactMap),
  )

  fun requireSuccessfulCommand(
    requiredCommand: String,
    sourceLabel: String,
  ): FeatureTaskRuntimeValidationCommandResult {
    val result = results.lastOrNull { it.command == requiredCommand }
      ?: throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
        sourceLabel,
        "missing required validation command result '$requiredCommand'.",
      )
    if (result.exitCode != 0) {
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
        sourceLabel,
        "required validation command '$requiredCommand' exited with ${result.exitCode}.",
      )
    }
    return result
  }

  fun requireSuccessfulResult(sourceLabel: String): FeatureTaskRuntimeValidationCommandResult {
    val result = results.lastOrNull()
      ?: throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
        sourceLabel,
        "validation evidence has no command results.",
      )
    if (result.exitCode != 0) {
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
        sourceLabel,
        "the final validation command '${result.command}' exited with ${result.exitCode}.",
      )
    }
    return result
  }

  companion object {
    @OpenBoundaryMap("Runtime-owned validation evidence decode from durable workflow artifacts")
    fun fromArtifactMap(raw: Map<String, Any?>, sourceLabel: String): FeatureTaskRuntimeValidationEvidence {
      val allowed = setOf(
        ValidationEvidencePayloadKeys.CONTRACT_VERSION,
        ValidationEvidencePayloadKeys.RESULTS,
      )
      val unknown = raw.keys - allowed
      if (unknown.isNotEmpty()) invalid(sourceLabel, "unknown keys ${unknown.sorted()}.")
      val version = raw[ValidationEvidencePayloadKeys.CONTRACT_VERSION] as? String
        ?: invalid(sourceLabel, "contract_version is missing.")
      if (version != FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION) {
        invalid(
          sourceLabel,
          "unsupported contract_version '$version'; expected " +
            "'$FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION'.",
        )
      }
      val rawResults = raw[ValidationEvidencePayloadKeys.RESULTS] as? List<*>
        ?: invalid(sourceLabel, "results must be a list.")
      val results = rawResults.mapIndexed { index, item ->
        val result = item as? Map<*, *> ?: invalid(sourceLabel, "results[$index] must be a mapping.")
        if (result.keys.any { it !is String }) {
          invalid(sourceLabel, "results[$index] has a non-string key.")
        }
        val unknownResultKeys = result.keys
          .filterIsInstance<String>()
          .toSet() - setOf(
          ValidationEvidencePayloadKeys.COMMAND,
          ValidationEvidencePayloadKeys.EXIT_CODE,
        )
        if (unknownResultKeys.isNotEmpty()) {
          invalid(sourceLabel, "results[$index] has unknown keys ${unknownResultKeys.sorted()}.")
        }
        val command = result[ValidationEvidencePayloadKeys.COMMAND] as? String
          ?: invalid(sourceLabel, "results[$index].command must be a string.")
        val exitCode = result[ValidationEvidencePayloadKeys.EXIT_CODE].asIntegerOrNull()
          ?: invalid(sourceLabel, "results[$index].exit_code must be an integer.")
        if (command.isBlank()) invalid(sourceLabel, "results[$index].command must be non-blank.")
        FeatureTaskRuntimeValidationCommandResult(command, exitCode)
      }
      return try {
        FeatureTaskRuntimeValidationEvidence(results)
      } catch (error: IllegalArgumentException) {
        invalid(sourceLabel, error.message.orEmpty())
      }
    }

    private fun invalid(sourceLabel: String, reason: String): Nothing =
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(sourceLabel, reason)
  }
}

private fun Any?.asIntegerOrNull(): Int? = when (this) {
  is Int -> this
  is Long -> toInt().takeIf { it.toLong() == this }
  is Short -> toInt()
  is Byte -> toInt()
  else -> null
}
