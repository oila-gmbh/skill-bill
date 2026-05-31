package skillbill.featurespec.model

enum class FeatureSpecPreparationMode(val wireValue: String) {
  SINGLE_SPEC("single_spec"),
  DECOMPOSED("decomposed"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureSpecPreparationMode? =
      entries.firstOrNull { mode -> mode.wireValue == value }
  }
}

data class FeatureSpecPreparationIntake(
  val issueKey: String,
  val intendedOutcome: String,
  val acceptanceCriteria: List<String>,
  val constraints: List<String>,
  val nonGoals: List<String> = emptyList(),
)

data class FeatureSpecPreparationDecision(
  val issueKey: String,
  val intendedOutcome: String,
  val acceptanceCriteria: List<String>,
  val constraints: List<String>,
  val nonGoals: List<String>,
  val mode: FeatureSpecPreparationMode,
)

data class FeatureSpecSubtaskPreparation(
  val id: Int,
  val name: String,
  val scope: String,
  val acceptanceCriteria: List<String>,
  val nonGoals: List<String>,
  val dependencyNotes: String,
  val validationStrategy: String,
  val nextPath: String,
  val dependsOn: List<Int> = emptyList(),
)

data class FeatureSpecWriteRequest(
  val decision: FeatureSpecPreparationDecision,
  val featureName: String,
  val parentSpecOverview: String,
  val validationStrategy: String,
  val subtasks: List<FeatureSpecSubtaskPreparation> = emptyList(),
  val baseBranch: String = "main",
  val featureBranch: String = "",
)

data class FeatureSpecWriteResult(
  val mode: FeatureSpecPreparationMode,
  val parentSpecPath: String,
  val featureImplementPath: String,
  val decompositionManifestPath: String?,
  val subtaskSpecPaths: List<String>,
)
