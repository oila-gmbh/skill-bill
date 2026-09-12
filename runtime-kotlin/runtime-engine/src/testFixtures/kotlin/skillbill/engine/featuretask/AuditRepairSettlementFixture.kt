package skillbill.engine.featuretask

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import java.time.Clock

fun FeatureTaskPhaseSettlementService(
  repository: FeatureTaskPhaseSettlementRepository,
  clock: Clock,
  cycles: AuditRepairCycleRepository,
): FeatureTaskPhaseSettlementService = FeatureTaskPhaseSettlementService(
  repository,
  clock,
  cycles,
  object : FeatureTaskRuntimeAcceptanceCriteriaSource {
    override fun cycleRequired(workflowId: String): Boolean = false
    override fun criterionRefs(workflowId: String): List<String>? = null
  },
  object : AuditRepairCheckpointCoordinator {
    override fun verifyCurrent(checkpoint: AuditRepairCheckpoint): Boolean = true
    override fun verifyRetained(checkpoint: AuditRepairCheckpoint): Boolean = true
  },
  object : RuntimeDiagnostics {
    override fun warning(message: String, error: Throwable?) = Unit
    override fun error(message: String, error: Throwable?) = Unit
  },
)
