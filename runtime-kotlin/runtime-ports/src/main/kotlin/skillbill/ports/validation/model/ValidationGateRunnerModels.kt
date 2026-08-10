package skillbill.ports.validation.model

import skillbill.scaffold.model.ValidationGateDeclaration
import java.nio.file.Path

enum class ValidationGateCacheMode(val wireValue: String) {
  CACHE_ELIGIBLE("cache_eligible"),
  FORCED_FULL("forced_full"),
  ;

  companion object {
    fun fromWire(value: String): ValidationGateCacheMode? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ValidationGateRunOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  REJECTED_ZERO_WORK("rejected_zero_work"),
  ;

  companion object {
    fun fromWire(value: String): ValidationGateRunOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class ValidationGateFinding(
  val module: String,
  val ruleOrTestId: String,
  val message: String,
  val location: String?,
)

data class ValidationGateRunRequest(
  val repoRoot: Path,
  val argv: List<String>,
  val cacheMode: ValidationGateCacheMode,
  val declaration: ValidationGateDeclaration,
  val terminalVerifying: Boolean,
)

data class ValidationGateRunResult(
  val exitCode: Int,
  val durationMs: Long,
  val outcome: ValidationGateRunOutcome,
  val cacheMode: ValidationGateCacheMode,
  val executedWorkUnits: Int,
  val findings: List<ValidationGateFinding>,
)

data class ValidationGateRunMeasurement(
  val durationMs: Long,
  val outcome: ValidationGateRunOutcome,
  val cacheMode: ValidationGateCacheMode,
  val executedWorkUnits: Int,
) {
  @Suppress("unused")
  fun toReceiptMap(): Map<String, Any?> = linkedMapOf(
    "duration_ms" to durationMs,
    "outcome" to outcome.wireValue,
    "cache_mode" to cacheMode.wireValue,
    "executed_work_units" to executedWorkUnits,
  )
}
