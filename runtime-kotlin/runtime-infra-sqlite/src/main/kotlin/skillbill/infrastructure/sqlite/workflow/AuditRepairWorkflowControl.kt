package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonCodec
import skillbill.error.AuditRepairCycleConflictError
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPauseKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.progressSince
import java.sql.Connection

internal class AuditRepairWorkflowControl(private val connection: Connection) {
  fun advance(previous: AuditRepairCycle, next: AuditRepairCycle) {
    val workflowId = next.identity.workflowId
    val artifacts = readArtifacts(workflowId)
    val progress = next.current.assessment?.let { assessment ->
      previous.latestAssessment?.let(assessment::progressSince)
    }
    if (previous.current.stage == AuditRepairStage.PAUSED) consumeGrant(artifacts)
    if (next.current.stage == AuditRepairStage.PAUSED) {
      artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY] = FeatureTaskRuntimeAuditGapPause(
        pauseKind = FeatureTaskRuntimeAuditGapPauseKind.NO_PROGRESS,
        reason = progress?.reason ?: requireNotNull(next.current.reason),
        edgeIteration = next.repairRoundCount.coerceAtLeast(1),
      ).toArtifactMap()
    }
    next.current.assessment?.let { assessment ->
      artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY] = FeatureTaskRuntimeAuditGapProgress(
        criterionRefs = assessment.unmetCriterionRefs,
        repositoryFingerprint = assessment.checkpoint.repositoryFingerprint,
      ).toArtifactMap()
    }
    connection.prepareStatement(
      "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, JsonCodec.mapToJsonString(artifacts))
      statement.setString(2, workflowId)
      if (statement.executeUpdate() != 1) throw AuditRepairCycleConflictError("Audit workflow was lost.")
    }
  }

  private fun readArtifacts(workflowId: String): MutableMap<String, Any?> = connection.prepareStatement(
    "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) throw AuditRepairCycleConflictError("Audit workflow is absent.")
      val root = JsonCodec.parseObjectOrNull(rows.getString(1))
        ?: throw AuditRepairCycleConflictError("Audit workflow artifacts are malformed.")
      root.mapValues { JsonCodec.jsonElementToValue(it.value) }.toMutableMap()
    }
  }

  private fun consumeGrant(artifacts: MutableMap<String, Any?>) {
    val raw = JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY])
      ?: throw AuditRepairCycleConflictError("A paused audit requires an operator decision.")
    val pause = FeatureTaskRuntimeAuditGapPause.fromArtifactMap(raw)
    if (pause.grantConsumed || pause.operatorDecision != AUDIT_GAP_PAUSE_DECISION_RETRY_FIX) {
      throw AuditRepairCycleConflictError("A paused audit requires a fresh retry_fix grant.")
    }
    artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY] =
      pause.copy(grantConsumed = true, operatorDecision = null).toArtifactMap()
  }
}
