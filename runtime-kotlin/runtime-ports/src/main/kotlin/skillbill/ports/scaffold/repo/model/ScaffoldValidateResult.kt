package skillbill.ports.scaffold.repo.model

data class ScaffoldValidateResult(
  val repoRoot: String,
  val mode: ScaffoldValidationMode,
  val status: ScaffoldValidationStatus,
  val issues: List<String>,
  val skillNames: List<String>? = null,
  val suggestedCommands: List<String>? = null,
)

enum class ScaffoldValidationMode(val wireValue: String) {
  REPOSITORY("repo"),
  SELECTED("selected");

  companion object {
    fun fromWire(value: String): ScaffoldValidationMode? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ScaffoldValidationStatus(val wireValue: String) {
  PASS("pass"),
  FAIL("fail");

  companion object {
    fun fromWire(value: String): ScaffoldValidationStatus? = entries.firstOrNull { it.wireValue == value }
  }
}
