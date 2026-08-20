package skillbill.workflow.model

enum class ValidationDepth(val wireValue: String) {
  FULL("full"),
  ;

  companion object {
    val DEFAULT: ValidationDepth = FULL

    private const val RETIRED_BUILD_ONLY_WIRE_VALUE: String = "build_only"

    private val DECODABLE_WIRE_VALUES: Map<String, ValidationDepth> =
      entries.associateBy(ValidationDepth::wireValue) + (RETIRED_BUILD_ONLY_WIRE_VALUE to FULL)

    fun fromWire(value: String): ValidationDepth = requireNotNull(DECODABLE_WIRE_VALUES[value]) {
      "Unknown validation depth '$value'. Allowed: ${entries.joinToString { it.wireValue }}."
    }
  }
}
