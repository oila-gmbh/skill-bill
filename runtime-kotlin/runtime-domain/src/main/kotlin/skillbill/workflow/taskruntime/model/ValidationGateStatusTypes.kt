package skillbill.workflow.taskruntime.model

enum class ValidationGateCacheMode(val wireValue: String) {
  CACHE_ELIGIBLE("cache_eligible"),
  FORCED_FULL("forced_full"),
  WARM("warm"),
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
