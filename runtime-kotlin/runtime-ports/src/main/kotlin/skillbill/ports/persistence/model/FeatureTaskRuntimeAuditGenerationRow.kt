package skillbill.ports.persistence.model

/**
 * One durable append-only audit-generation row. The payload is the bounded generation projection encoded
 * against the canonical audit-generation contract; the columns lifted out of it are exactly the ones the
 * store keys and orders on.
 */
data class FeatureTaskRuntimeAuditGenerationRow(
  val workflowId: String,
  val generationOrdinal: Int,
  val repositoryCheckpoint: String,
  val contractVersion: String,
  val generationJson: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflow_id must be nonblank." }
    require(generationOrdinal >= 1) { "generation_ordinal must be 1-based, was $generationOrdinal." }
    require(repositoryCheckpoint.isNotBlank()) { "repository_checkpoint must be nonblank." }
    require(contractVersion.isNotBlank()) { "contract_version must be nonblank." }
    require(generationJson.isNotBlank()) { "generation_json must be nonblank." }
  }
}
