package skillbill.db.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION
import skillbill.db.core.inImmediateTransaction
import skillbill.ports.taskruntime.FeatureTaskRuntimeAdaptiveDecisionStore
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveDecisionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecompositionRequirement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCategory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheck
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGovernedDirectOverride
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairBatch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedReviewMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedReviewPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSubstanceDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSizingDecision
import java.sql.Connection

/**
 * Persists the bounded adaptive projection and its authoritative destination in one transaction.
 * Stable decision and repair-batch identities make retries replace-or-ignore instead of duplicating evidence.
 */
class FeatureTaskRuntimeAdaptiveDecisionSqliteStore(
  private val connection: Connection,
  private val mapper: ObjectMapper = ObjectMapper(),
) : FeatureTaskRuntimeAdaptiveDecisionStore {
  override fun read(decisionId: String): FeatureTaskRuntimeAdaptiveDecisionRecord? = connection.prepareStatement(
    "SELECT decision_json FROM feature_task_runtime_adaptive_decisions WHERE decision_id = ?",
  ).use { statement ->
    statement.setString(1, decisionId)
    statement.executeQuery().use { rows ->
      if (rows.next()) decode(mapper.readTree(rows.getString("decision_json"))) else null
    }
  }

  override fun persistAndAdvance(record: FeatureTaskRuntimeAdaptiveDecisionRecord, destinationPhaseId: String) {
    require(destinationPhaseId in DESTINATIONS)
    val outcome = record.focusedQualityOutcome
    connection.inImmediateTransaction {
      prepareStatement(
        """
        INSERT INTO feature_task_runtime_adaptive_decisions(
          decision_id, contract_version, decision_json, destination_phase_id,
          semantic_fingerprint, focused_quality_disposition, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT(decision_id) DO UPDATE SET
          decision_json = excluded.decision_json,
          destination_phase_id = excluded.destination_phase_id,
          semantic_fingerprint = excluded.semantic_fingerprint,
          focused_quality_disposition = excluded.focused_quality_disposition,
          updated_at = CURRENT_TIMESTAMP
        """.trimIndent(),
      ).use {
        var parameterIndex = 1
        it.setString(parameterIndex++, record.decisionId)
        it.setString(parameterIndex++, FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION)
        it.setString(parameterIndex++, mapper.writeValueAsString(encode(record)))
        it.setString(parameterIndex++, destinationPhaseId)
        it.setString(
          parameterIndex++,
          outcome?.checkpoint?.semanticFingerprint ?: outcome?.repairBatch?.checkpointFingerprint,
        )
        it.setString(parameterIndex, outcome?.disposition?.name)
        it.executeUpdate()
      }
      outcome?.repairBatch?.let { batch ->
        prepareStatement(
          """
          INSERT OR IGNORE INTO feature_task_runtime_quality_repair_batches(
            batch_id, decision_id, checkpoint_fingerprint, attempt, batch_json
          ) VALUES (?, ?, ?, ?, ?)
          """.trimIndent(),
        ).use {
          var parameterIndex = 1
          it.setString(parameterIndex++, batch.batchId)
          it.setString(parameterIndex++, record.decisionId)
          it.setString(parameterIndex++, batch.checkpointFingerprint)
          it.setInt(parameterIndex++, batch.attempt)
          it.setString(parameterIndex, mapper.writeValueAsString(encodeBatch(batch)))
          it.executeUpdate()
        }
      }
    }
  }

  private fun encode(record: FeatureTaskRuntimeAdaptiveDecisionRecord): Map<String, Any?> = mapOf(
    "decision_id" to record.decisionId,
    "sizing" to mapOf(
      "feature_size" to record.sizingDecision.featureSize.name,
      "score" to record.sizingDecision.score,
      "decomposition_requirement" to record.sizingDecision.decompositionRequirement.name,
      "rationale" to record.sizingDecision.rationale,
    ),
    "override" to record.directOverride?.let {
      mapOf(
        "id" to it.overrideId,
        "policy_version" to it.policyVersion,
        "rationale" to it.rationale,
        "persisted" to it.persisted,
      )
    },
    "review" to mapOf(
      "minimum_depth" to record.reviewPolicy.minimumDepth.name,
      "execution_mode" to record.reviewPolicy.executionMode.name,
      "specialist_areas" to record.reviewPolicy.requiredSpecialistAreas.sorted(),
      "rationale" to record.reviewPolicy.rationale,
    ),
    "focused_quality" to record.focusedQualityOutcome?.let(::encodeOutcome),
  )

  private fun encodeOutcome(outcome: FeatureTaskRuntimeFocusedQualityOutcome): Map<String, Any?> = mapOf(
    "disposition" to outcome.disposition.name,
    "checkpoint" to outcome.checkpoint?.let {
      mapOf(
        "checkpoint_fingerprint" to it.checkpointFingerprint,
        "semantic_fingerprint" to it.semanticFingerprint,
        "passed" to it.passed,
        "checks" to it.checks.map(::encodeCheck),
      )
    },
    "repair_batch" to outcome.repairBatch?.let(::encodeBatch),
  )

  private fun encodeCheck(check: FeatureTaskRuntimeFocusedQualityCheck): Map<String, Any?> = mapOf(
    "check_id" to check.checkId,
    "category" to check.category.name,
    "owned_paths" to check.ownedPaths,
    "checker_skill" to check.checkerSkill,
  )

  private fun encodeBatch(batch: FeatureTaskRuntimeQualityRepairBatch): Map<String, Any?> = mapOf(
    "batch_id" to batch.batchId,
    "checkpoint_fingerprint" to batch.checkpointFingerprint,
    "attempt" to batch.attempt,
    "items" to batch.items.map {
      mapOf(
        "item_id" to it.itemId,
        "check_id" to it.checkId,
        "category" to it.category.name,
        "bounded_diagnostic" to it.boundedDiagnostic,
      )
    },
  )

  private fun decode(root: JsonNode): FeatureTaskRuntimeAdaptiveDecisionRecord {
    val sizing = root["sizing"]
    val review = root["review"]
    return FeatureTaskRuntimeAdaptiveDecisionRecord(
      decisionId = root["decision_id"].asText(),
      sizingDecision = FeatureTaskRuntimeSizingDecision(
        FeatureTaskRuntimeFeatureSize.valueOf(sizing["feature_size"].asText()),
        sizing["score"].asInt(),
        FeatureTaskRuntimeDecompositionRequirement.valueOf(sizing["decomposition_requirement"].asText()),
        sizing["rationale"].map(JsonNode::asText),
      ),
      directOverride = root["override"]?.takeUnless(JsonNode::isNull)?.let {
        FeatureTaskRuntimeGovernedDirectOverride(
          it["id"].asText(),
          it["policy_version"].asText(),
          it["rationale"].asText(),
          it["persisted"].asBoolean(),
        )
      },
      reviewPolicy = FeatureTaskRuntimeResolvedReviewPolicy(
        FeatureTaskRuntimeReviewSubstanceDepth.valueOf(review["minimum_depth"].asText()),
        FeatureTaskRuntimeResolvedReviewMode.valueOf(review["execution_mode"].asText()),
        review["specialist_areas"].map(JsonNode::asText).toSet(),
        review["rationale"].map(JsonNode::asText),
      ),
      focusedQualityOutcome = root["focused_quality"]?.takeUnless(JsonNode::isNull)?.let(::decodeOutcome),
    )
  }

  private fun decodeOutcome(node: JsonNode): FeatureTaskRuntimeFocusedQualityOutcome =
    FeatureTaskRuntimeFocusedQualityOutcome(
      FeatureTaskRuntimeFocusedQualityDisposition.valueOf(node["disposition"].asText()),
      node["checkpoint"]?.takeUnless(JsonNode::isNull)?.let {
        FeatureTaskRuntimeFocusedQualityCheckpoint(
          it["checkpoint_fingerprint"].asText(),
          it["semantic_fingerprint"].asText(),
          it["checks"].map(::decodeCheck),
          it["passed"].asBoolean(),
        )
      },
      node["repair_batch"]?.takeUnless(JsonNode::isNull)?.let(::decodeBatch),
    )

  private fun decodeCheck(node: JsonNode) = FeatureTaskRuntimeFocusedQualityCheck(
    node["check_id"].asText(),
    FeatureTaskRuntimeFocusedQualityCategory.valueOf(node["category"].asText()),
    node["owned_paths"].map(JsonNode::asText),
    node["checker_skill"].asText(),
  )

  private fun decodeBatch(node: JsonNode) = FeatureTaskRuntimeQualityRepairBatch(
    node["batch_id"].asText(),
    node["checkpoint_fingerprint"].asText(),
    node["attempt"].asInt(),
    node["items"].map {
      FeatureTaskRuntimeQualityRepairItem(
        it["item_id"].asText(),
        it["check_id"].asText(),
        FeatureTaskRuntimeFocusedQualityCategory.valueOf(it["category"].asText()),
        it["bounded_diagnostic"].asText(),
      )
    },
  )

  private companion object {
    val DESTINATIONS = setOf("implement", "review")
  }
}
