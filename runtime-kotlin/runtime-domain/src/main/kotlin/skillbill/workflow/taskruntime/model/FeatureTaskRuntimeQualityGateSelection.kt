package skillbill.workflow.taskruntime.model

enum class FeatureTaskRuntimeQualityGateSelection(val wireValue: String) {
  BUILD("build"),
  VALIDATE("validate"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskRuntimeQualityGateSelection = when (value) {
      BUILD.wireValue -> BUILD
      null, VALIDATE.wireValue -> VALIDATE
      else -> VALIDATE
    }
  }
}
