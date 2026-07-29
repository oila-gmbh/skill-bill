package skillbill.ports.persistence

import skillbill.ports.persistence.model.AuditRepairItemResult
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapDisposition
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem

interface AuditGenerationStore {
  fun persist(generation: AuditGeneration): AuditGeneration
  fun getLatest(workflowId: String): AuditGeneration?
  fun getByGeneration(workflowId: String, generation: Int): AuditGeneration?
  fun listAll(workflowId: String): List<AuditGeneration>
}

interface AuditRepairBatchStore {
  fun persist(batch: AuditRepairBatch): AuditRepairBatch
  fun getActive(workflowId: String): AuditRepairBatch?
  fun getByGenerationId(generationId: String): AuditRepairBatch?
  fun listByWorkflow(workflowId: String): List<AuditRepairBatch>
  fun deactivate(batchId: String): Boolean
}

interface AuditRepairQuery {
  fun getUnresolvedRepairItems(workflowId: String): List<AuditRepairItem>
  fun getUnresolvedRepairItemsWithDependencies(workflowId: String): Map<AuditRepairItem, List<AuditRepairItem>>
  fun getPriorResults(workflowId: String, itemId: String): List<AuditRepairItemResult>
  fun getNonRegressionConstraints(workflowId: String, itemId: String): List<String>
  fun getGapDisposition(workflowId: String, gapId: String): AuditGapDisposition?
  fun getAllGapDispositions(workflowId: String): List<AuditGapDisposition>
  fun getRecurringGaps(workflowId: String): List<AuditGap>
  fun getResolvedGaps(workflowId: String): List<AuditGap>
}

object UnavailableAuditGenerationStore : AuditGenerationStore {
  private fun unavailable(): Nothing = error("Audit generation persistence is not available.")
  override fun persist(generation: AuditGeneration): AuditGeneration = unavailable()
  override fun getLatest(workflowId: String): AuditGeneration? = unavailable()
  override fun getByGeneration(workflowId: String, generation: Int): AuditGeneration? = unavailable()
  override fun listAll(workflowId: String): List<AuditGeneration> = unavailable()
}

object UnavailableAuditRepairBatchStore : AuditRepairBatchStore {
  private fun unavailable(): Nothing = error("Audit repair batch persistence is not available.")
  override fun persist(batch: AuditRepairBatch): AuditRepairBatch = unavailable()
  override fun getActive(workflowId: String): AuditRepairBatch? = unavailable()
  override fun getByGenerationId(generationId: String): AuditRepairBatch? = unavailable()
  override fun listByWorkflow(workflowId: String): List<AuditRepairBatch> = unavailable()
  override fun deactivate(batchId: String): Boolean = unavailable()
}

object UnavailableAuditRepairQuery : AuditRepairQuery {
  private fun unavailable(): Nothing = error("Audit repair query is not available.")
  override fun getUnresolvedRepairItems(workflowId: String): List<AuditRepairItem> = unavailable()
  override fun getUnresolvedRepairItemsWithDependencies(
    workflowId: String,
  ): Map<AuditRepairItem, List<AuditRepairItem>> = unavailable()
  override fun getPriorResults(workflowId: String, itemId: String): List<AuditRepairItemResult> = unavailable()
  override fun getNonRegressionConstraints(workflowId: String, itemId: String): List<String> = unavailable()
  override fun getGapDisposition(workflowId: String, gapId: String): AuditGapDisposition? = unavailable()
  override fun getAllGapDispositions(workflowId: String): List<AuditGapDisposition> = unavailable()
  override fun getRecurringGaps(workflowId: String): List<AuditGap> = unavailable()
  override fun getResolvedGaps(workflowId: String): List<AuditGap> = unavailable()
}
