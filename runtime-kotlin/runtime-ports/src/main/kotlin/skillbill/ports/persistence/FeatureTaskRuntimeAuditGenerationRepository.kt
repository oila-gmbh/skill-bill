package skillbill.ports.persistence

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

/**
 * Insert-only durable authority for completeness-audit history.
 *
 * The port exposes no update and no delete by construction: append-only is a property of the interface, not
 * a convention callers are trusted to follow. Duplicate ordinals for one workflow are rejected rather than
 * overwritten, so a crash-retried append cannot silently replace the generation it is retrying.
 */
interface FeatureTaskRuntimeAuditGenerationRepository {
  /**
   * Appends one generation. Fails when the workflow already carries this ordinal; the caller reads the
   * history first and re-derives the next ordinal, so a collision is a real concurrent-writer violation.
   */
  fun append(row: FeatureTaskRuntimeAuditGenerationRow)

  /** Every generation for one workflow in ascending ordinal order. */
  fun listOrdered(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow>

  /**
   * Discards a workflow's entire generation history so it can be regenerated in band. Reachable only from
   * the quarantine-and-regenerate edge for a legacy workflow whose history predates this contract; ordinary
   * settlement has no path to it.
   */
  fun quarantineAll(workflowId: String): Int
}

object UnavailableFeatureTaskRuntimeAuditGenerationRepository : FeatureTaskRuntimeAuditGenerationRepository {
  private const val REASON: String =
    "Feature-task-runtime audit-generation persistence is not available on this unit of work."

  override fun append(row: FeatureTaskRuntimeAuditGenerationRow): Nothing = error(REASON)

  override fun listOrdered(workflowId: String): Nothing = error(REASON)

  override fun quarantineAll(workflowId: String): Nothing = error(REASON)
}
