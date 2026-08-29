package skillbill.db.workflow

internal data class StoredRecoveryIdentity(
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val provenanceTuple: List<String>,
)
