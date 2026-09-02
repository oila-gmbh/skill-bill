package skillbill.application.featuretask

internal data class CheckpointScopePreparation(
  val worktreeDelta: List<String>,
  val stagedPaths: List<String>,
  val phaseWritten: List<String>,
  val writingIntroduced: List<String>,
  val seedOwned: List<String>,
  val deletedPaths: List<String>,
)
