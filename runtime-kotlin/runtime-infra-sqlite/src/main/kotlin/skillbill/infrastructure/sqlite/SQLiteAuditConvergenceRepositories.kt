@file:Suppress("MagicNumber", "TooManyFunctions")

package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.AuditGenerationStore
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.ports.persistence.model.AuditRepairItemResult
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapDisposition
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditGenerationIdentities
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.RepositoryCheckpoint
import java.sql.Connection

class SQLiteAuditGenerationStore(
  private val connection: Connection,
) : AuditGenerationStore {
  override fun persist(generation: AuditGeneration): AuditGeneration {
    val existing = getLatest(generation.workflowId)
    require(existing == null || existing.generation < generation.generation) {
      "Cannot persist generation ${generation.generation} when generation ${existing?.generation} already exists."
    }

    connection.prepareStatement(
      """
      INSERT INTO feature_task_audit_generations(
        generation_id, workflow_id, generation, repository_fingerprint, repository_evidence_ref,
        created_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, generation) DO UPDATE SET
        repository_fingerprint = excluded.repository_fingerprint,
        repository_evidence_ref = excluded.repository_evidence_ref
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, generation.generationId)
      stmt.setString(2, generation.workflowId)
      stmt.setInt(3, generation.generation)
      stmt.setString(4, generation.repositoryCheckpoint.fingerprint)
      stmt.setString(5, generation.repositoryCheckpoint.evidenceRef)
      stmt.setString(6, generation.createdAt)
      stmt.executeUpdate()
    }

    persistSatisfiedCriteria(connection, generation)
    persistGaps(connection, generation)
    persistRepairBatch(connection, generation.repairBatch)

    return generation
  }

  override fun getLatest(workflowId: String): AuditGeneration? = connection.prepareStatement(
    """
      SELECT generation_id, workflow_id, generation, repository_fingerprint, repository_evidence_ref,
             created_at
      FROM feature_task_audit_generations
      WHERE workflow_id = ?
      ORDER BY generation DESC
      LIMIT 1
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      if (rs.next()) {
        val generationId = rs.getString("generation_id")
        val gen = rs.getInt("generation")
        val checkpoint = RepositoryCheckpoint(
          fingerprint = rs.getString("repository_fingerprint"),
          evidenceRef = rs.getString("repository_evidence_ref"),
        )
        val createdAt = rs.getString("created_at")
        val satisfiedCriteria = getSatisfiedCriteria(connection, workflowId, gen)
        val gaps = getGaps(connection, workflowId, gen)
        val repairBatch = getRepairBatch(connection, generationId)
        AuditGeneration(
          generationId = generationId,
          workflowId = workflowId,
          generation = gen,
          repositoryCheckpoint = checkpoint,
          satisfiedCriterionRefs = satisfiedCriteria,
          gaps = gaps,
          repairBatch = repairBatch,
          createdAt = createdAt,
        )
      } else {
        null
      }
    }
  }

  override fun getByGeneration(workflowId: String, generation: Int): AuditGeneration? {
    val generationId = AuditGenerationIdentities.generationId(workflowId, generation)
    return connection.prepareStatement(
      """
      SELECT repository_fingerprint, repository_evidence_ref, created_at
      FROM feature_task_audit_generations
      WHERE workflow_id = ? AND generation = ?
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setInt(2, generation)
      stmt.executeQuery().use { rs ->
        if (rs.next()) {
          val checkpoint = RepositoryCheckpoint(
            fingerprint = rs.getString("repository_fingerprint"),
            evidenceRef = rs.getString("repository_evidence_ref"),
          )
          val createdAt = rs.getString("created_at")
          val satisfiedCriteria = getSatisfiedCriteria(connection, workflowId, generation)
          val gaps = getGaps(connection, workflowId, generation)
          val repairBatch = getRepairBatch(connection, generationId)
          AuditGeneration(
            generationId = generationId,
            workflowId = workflowId,
            generation = generation,
            repositoryCheckpoint = checkpoint,
            satisfiedCriterionRefs = satisfiedCriteria,
            gaps = gaps,
            repairBatch = repairBatch,
            createdAt = createdAt,
          )
        } else {
          null
        }
      }
    }
  }

  override fun listAll(workflowId: String): List<AuditGeneration> = connection.prepareStatement(
    """
      SELECT generation_id, generation, repository_fingerprint, repository_evidence_ref, created_at
      FROM feature_task_audit_generations
      WHERE workflow_id = ?
      ORDER BY generation ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      val generations = mutableListOf<AuditGeneration>()
      while (rs.next()) {
        val generationId = rs.getString("generation_id")
        val gen = rs.getInt("generation")
        val checkpoint = RepositoryCheckpoint(
          fingerprint = rs.getString("repository_fingerprint"),
          evidenceRef = rs.getString("repository_evidence_ref"),
        )
        val createdAt = rs.getString("created_at")
        val satisfiedCriteria = getSatisfiedCriteria(connection, workflowId, gen)
        val gaps = getGaps(connection, workflowId, gen)
        val repairBatch = getRepairBatch(connection, generationId)
        generations.add(
          AuditGeneration(
            generationId = generationId,
            workflowId = workflowId,
            generation = gen,
            repositoryCheckpoint = checkpoint,
            satisfiedCriterionRefs = satisfiedCriteria,
            gaps = gaps,
            repairBatch = repairBatch,
            createdAt = createdAt,
          ),
        )
      }
      generations
    }
  }

  private fun persistSatisfiedCriteria(connection: Connection, generation: AuditGeneration) {
    generation.satisfiedCriterionRefs.forEach { criterionRef ->
      connection.prepareStatement(
        """
        INSERT OR IGNORE INTO feature_task_audit_satisfied_criteria(
          workflow_id, generation, criterion_ref
        ) VALUES (?, ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, generation.workflowId)
        stmt.setInt(2, generation.generation)
        stmt.setString(3, criterionRef)
        stmt.executeUpdate()
      }
    }
  }

  private fun getSatisfiedCriteria(connection: Connection, workflowId: String, generation: Int): List<String> =
    connection.prepareStatement(
      """
      SELECT criterion_ref
      FROM feature_task_audit_satisfied_criteria
      WHERE workflow_id = ? AND generation = ?
      ORDER BY criterion_ref ASC
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setInt(2, generation)
      stmt.executeQuery().use { rs ->
        val criteria = mutableListOf<String>()
        while (rs.next()) {
          criteria.add(rs.getString("criterion_ref"))
        }
        criteria
      }
    }

  private fun persistGaps(connection: Connection, generation: AuditGeneration) {
    generation.gaps.forEach { gap ->
      connection.prepareStatement(
        """
        INSERT OR REPLACE INTO feature_task_audit_gaps(
          gap_id, workflow_id, generation, acceptance_criterion_ref, acceptance_criterion_text,
          diagnosis, affected_boundary, status, recurrence, first_seen_generation,
          failure_observation, failure_artifact_ref, failure_check_ref
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, gap.gapId)
        stmt.setString(2, generation.workflowId)
        stmt.setInt(3, generation.generation)
        stmt.setString(4, gap.acceptanceCriterionRef)
        stmt.setString(5, gap.acceptanceCriterionText)
        stmt.setString(6, gap.diagnosis)
        stmt.setString(7, gap.affectedBoundary)
        stmt.setString(8, gap.status.name)
        stmt.setInt(9, gap.recurrence)
        stmt.setInt(10, gap.firstSeenGeneration)
        stmt.setString(11, gap.failureEvidence.observation.name)
        stmt.setString(12, gap.failureEvidence.artifactRef)
        stmt.setString(13, gap.failureEvidence.checkRef)
        stmt.executeUpdate()
      }
    }
  }

  private fun getGaps(connection: Connection, workflowId: String, generation: Int): List<AuditGap> =
    connection.prepareStatement(
      """
      SELECT gap_id, acceptance_criterion_ref, acceptance_criterion_text, diagnosis,
             affected_boundary, status, recurrence, first_seen_generation,
             failure_observation, failure_artifact_ref, failure_check_ref
      FROM feature_task_audit_gaps
      WHERE workflow_id = ? AND generation = ?
      ORDER BY gap_id ASC
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setInt(2, generation)
      stmt.executeQuery().use { rs ->
        val gaps = mutableListOf<AuditGap>()
        while (rs.next()) {
          val evidence = FeatureTaskRuntimeEvidence(
            observation = FeatureTaskRuntimeEvidence.Observation.valueOf(
              rs.getString("failure_observation"),
            ),
            artifactRef = rs.getString("failure_artifact_ref"),
            checkRef = rs.getString("failure_check_ref"),
          )
          gaps.add(
            AuditGap(
              gapId = rs.getString("gap_id"),
              acceptanceCriterionRef = rs.getString("acceptance_criterion_ref"),
              acceptanceCriterionText = rs.getString("acceptance_criterion_text"),
              failureEvidence = evidence,
              diagnosis = rs.getString("diagnosis"),
              affectedBoundary = rs.getString("affected_boundary"),
              status = AuditGapStatus.valueOf(rs.getString("status")),
              recurrence = rs.getInt("recurrence"),
              firstSeenGeneration = rs.getInt("first_seen_generation"),
            ),
          )
        }
        gaps
      }
    }

  private fun persistRepairBatch(connection: Connection, batch: AuditRepairBatch?) {
    if (batch == null) return
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO feature_task_audit_repair_batches(
        batch_id, generation_id, is_active
      ) VALUES (?, ?, ?)
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, batch.batchId)
      stmt.setString(2, batch.generationId)
      stmt.setBoolean(3, batch.isActive)
      stmt.executeUpdate()
    }

    batch.repairItems.forEach { item ->
      connection.prepareStatement(
        """
        INSERT OR REPLACE INTO feature_task_audit_repair_items(
          item_id, gap_id, intended_outcome, implementation_actions, affected_paths_or_symbols,
          required_verification, dependencies
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, item.itemId)
        stmt.setString(2, item.gapId)
        stmt.setString(3, item.intendedOutcome)
        stmt.setString(4, item.implementationActions.joinToString("\n"))
        stmt.setString(5, item.affectedPathsOrSymbols.joinToString("\n"))
        stmt.setString(6, item.requiredVerification.joinToString("\n"))
        stmt.setString(7, item.dependencies.joinToString("\n"))
        stmt.executeUpdate()
      }
    }
  }

  private fun getRepairBatch(connection: Connection, generationId: String): AuditRepairBatch? =
    connection.prepareStatement(
      """
      SELECT batch_id, is_active
      FROM feature_task_audit_repair_batches
      WHERE generation_id = ?
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, generationId)
      stmt.executeQuery().use { rs ->
        if (rs.next()) {
          val batchId = rs.getString("batch_id")
          val isActive = rs.getBoolean("is_active")
          val items = getRepairItems(connection, generationId)
          val dependencies = loadDependencies(connection, generationId)
          AuditRepairBatch(
            batchId = batchId,
            generationId = generationId,
            repairItems = items,
            dependencies = dependencies,
            isActive = isActive,
          )
        } else {
          null
        }
      }
    }

  private fun getRepairItems(connection: Connection, generationId: String): List<AuditRepairItem> =
    connection.prepareStatement(
      """
      SELECT item_id, gap_id, intended_outcome, implementation_actions, affected_paths_or_symbols,
             required_verification, dependencies
      FROM feature_task_audit_repair_items
      WHERE item_id IN (
        SELECT item_id FROM feature_task_audit_repair_item_batch_mapping WHERE generation_id = ?
      )
      ORDER BY item_id ASC
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, generationId)
      stmt.executeQuery().use { rs ->
        val items = mutableListOf<AuditRepairItem>()
        while (rs.next()) {
          items.add(
            AuditRepairItem(
              itemId = rs.getString("item_id"),
              gapId = rs.getString("gap_id"),
              intendedOutcome = rs.getString("intended_outcome"),
              implementationActions = rs.getString("implementation_actions").split("\n"),
              affectedPathsOrSymbols = rs.getString("affected_paths_or_symbols").split("\n"),
              requiredVerification = rs.getString("required_verification").split("\n"),
              dependencies = rs.getString("dependencies").split("\n").filter { it.isNotEmpty() },
            ),
          )
        }
        items
      }
    }

  private fun loadDependencies(connection: Connection, generationId: String): Map<String, List<String>> {
    val dependencies = mutableMapOf<String, MutableList<String>>()
    connection.prepareStatement(
      """
      SELECT item_id, depends_on_item_id
      FROM feature_task_audit_repair_item_dependencies
      WHERE generation_id = ?
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, generationId)
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          val itemId = rs.getString("item_id")
          val depId = rs.getString("depends_on_item_id")
          dependencies.getOrPut(itemId) { mutableListOf() }.add(depId)
        }
      }
    }
    return dependencies.mapValues { it.value.toList() }
  }
}

class SQLiteAuditRepairBatchStore(
  private val connection: Connection,
) : AuditRepairBatchStore {
  override fun persist(batch: AuditRepairBatch): AuditRepairBatch {
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO feature_task_audit_repair_batches(batch_id, generation_id, is_active)
      VALUES (?, ?, ?)
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, batch.batchId)
      stmt.setString(2, batch.generationId)
      stmt.setBoolean(3, batch.isActive)
      stmt.executeUpdate()
    }
    return batch
  }

  override fun getActive(workflowId: String): AuditRepairBatch? = connection.prepareStatement(
    """
      SELECT arb.batch_id, arb.generation_id, arb.is_active
      FROM feature_task_audit_repair_batches arb
      JOIN feature_task_audit_generations ag ON arb.generation_id = ag.generation_id
      WHERE ag.workflow_id = ? AND arb.is_active = 1
      LIMIT 1
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      if (rs.next()) {
        val batchId = rs.getString("batch_id")
        val generationId = rs.getString("generation_id")
        val isActive = rs.getBoolean("is_active")
        val items = getRepairItems(connection, generationId)
        val dependencies = loadDependencies(connection, generationId)
        AuditRepairBatch(
          batchId = batchId,
          generationId = generationId,
          repairItems = items,
          dependencies = dependencies,
          isActive = isActive,
        )
      } else {
        null
      }
    }
  }

  override fun getByGenerationId(generationId: String): AuditRepairBatch? = connection.prepareStatement(
    """
      SELECT batch_id, is_active
      FROM feature_task_audit_repair_batches
      WHERE generation_id = ?
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, generationId)
    stmt.executeQuery().use { rs ->
      if (rs.next()) {
        val batchId = rs.getString("batch_id")
        val isActive = rs.getBoolean("is_active")
        val items = getRepairItems(connection, generationId)
        val dependencies = loadDependencies(connection, generationId)
        AuditRepairBatch(
          batchId = batchId,
          generationId = generationId,
          repairItems = items,
          dependencies = dependencies,
          isActive = isActive,
        )
      } else {
        null
      }
    }
  }

  override fun listByWorkflow(workflowId: String): List<AuditRepairBatch> = connection.prepareStatement(
    """
      SELECT arb.batch_id, arb.generation_id, arb.is_active
      FROM feature_task_audit_repair_batches arb
      JOIN feature_task_audit_generations ag ON arb.generation_id = ag.generation_id
      WHERE ag.workflow_id = ?
      ORDER BY ag.generation ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      val batches = mutableListOf<AuditRepairBatch>()
      while (rs.next()) {
        val batchId = rs.getString("batch_id")
        val generationId = rs.getString("generation_id")
        val isActive = rs.getBoolean("is_active")
        val items = getRepairItems(connection, generationId)
        val dependencies = loadDependencies(connection, generationId)
        batches.add(
          AuditRepairBatch(
            batchId = batchId,
            generationId = generationId,
            repairItems = items,
            dependencies = dependencies,
            isActive = isActive,
          ),
        )
      }
      batches
    }
  }

  override fun deactivate(batchId: String): Boolean {
    val rows = connection.prepareStatement(
      "UPDATE feature_task_audit_repair_batches SET is_active = 0 WHERE batch_id = ?",
    ).use { stmt ->
      stmt.setString(1, batchId)
      stmt.executeUpdate()
    }
    return rows > 0
  }

  private fun getRepairItems(connection: Connection, generationId: String): List<AuditRepairItem> =
    connection.prepareStatement(
      """
    SELECT item_id, gap_id, intended_outcome, implementation_actions, affected_paths_or_symbols,
           required_verification, dependencies
    FROM feature_task_audit_repair_items
    WHERE item_id IN (
      SELECT item_id FROM feature_task_audit_repair_item_batch_mapping WHERE generation_id = ?
    )
    ORDER BY item_id ASC
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, generationId)
      stmt.executeQuery().use { rs ->
        val items = mutableListOf<AuditRepairItem>()
        while (rs.next()) {
          items.add(
            AuditRepairItem(
              itemId = rs.getString("item_id"),
              gapId = rs.getString("gap_id"),
              intendedOutcome = rs.getString("intended_outcome"),
              implementationActions = rs.getString("implementation_actions").split("\n"),
              affectedPathsOrSymbols = rs.getString("affected_paths_or_symbols").split("\n"),
              requiredVerification = rs.getString("required_verification").split("\n"),
              dependencies = rs.getString("dependencies").split("\n").filter { it.isNotEmpty() },
            ),
          )
        }
        items
      }
    }

  private fun loadDependencies(connection: Connection, generationId: String): Map<String, List<String>> {
    val dependencies = mutableMapOf<String, MutableList<String>>()
    connection.prepareStatement(
      """
      SELECT item_id, depends_on_item_id
      FROM feature_task_audit_repair_item_dependencies
      WHERE generation_id = ?
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, generationId)
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          val itemId = rs.getString("item_id")
          val depId = rs.getString("depends_on_item_id")
          dependencies.getOrPut(itemId) { mutableListOf() }.add(depId)
        }
      }
    }
    return dependencies.mapValues { it.value.toList() }
  }
}

class SQLiteAuditRepairQuery(
  private val connection: Connection,
) : AuditRepairQuery {
  override fun getUnresolvedRepairItems(workflowId: String): List<AuditRepairItem> = connection.prepareStatement(
    """
      SELECT ari.item_id, ari.gap_id, ari.intended_outcome, ari.implementation_actions,
             ari.affected_paths_or_symbols, ari.required_verification, ari.dependencies
      FROM feature_task_audit_repair_items ari
      JOIN feature_task_audit_repair_item_batch_mapping m ON ari.item_id = m.item_id
      JOIN feature_task_audit_repair_batches b ON m.generation_id = b.generation_id
      JOIN feature_task_audit_generations g ON b.generation_id = g.generation_id
      WHERE g.workflow_id = ? AND b.is_active = 1
      AND ari.item_id NOT IN (
        SELECT item_id FROM feature_task_audit_repair_item_results WHERE workflow_id = ?
      )
      ORDER BY ari.item_id ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.setString(2, workflowId)
    stmt.executeQuery().use { rs ->
      val items = mutableListOf<AuditRepairItem>()
      while (rs.next()) {
        items.add(
          AuditRepairItem(
            itemId = rs.getString("item_id"),
            gapId = rs.getString("gap_id"),
            intendedOutcome = rs.getString("intended_outcome"),
            implementationActions = rs.getString("implementation_actions").split("\n"),
            affectedPathsOrSymbols = rs.getString("affected_paths_or_symbols").split("\n"),
            requiredVerification = rs.getString("required_verification").split("\n"),
            dependencies = rs.getString("dependencies").split("\n").filter { it.isNotEmpty() },
          ),
        )
      }
      items
    }
  }

  override fun getUnresolvedRepairItemsWithDependencies(
    workflowId: String,
  ): Map<AuditRepairItem, List<AuditRepairItem>> {
    val allItems = getUnresolvedRepairItems(workflowId)
    val itemMap = allItems.associateBy { it.itemId }
    val result = mutableMapOf<AuditRepairItem, List<AuditRepairItem>>()

    allItems.forEach { item ->
      val deps = item.dependencies.mapNotNull { itemMap[it] }
      result[item] = deps
    }

    return result
  }

  override fun getPriorResults(itemId: String): List<AuditRepairItemResult> = connection.prepareStatement(
    """
      SELECT item_id, outcome, evidence_ref, verification_ref, disposition_generation
      FROM feature_task_audit_repair_item_results
      WHERE item_id = ?
      ORDER BY disposition_generation ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, itemId)
    stmt.executeQuery().use { rs ->
      val results = mutableListOf<AuditRepairItemResult>()
      while (rs.next()) {
        results.add(
          AuditRepairItemResult(
            itemId = rs.getString("item_id"),
            outcome = AuditRepairItemResult.Outcome.valueOf(rs.getString("outcome")),
            evidenceRef = rs.getString("evidence_ref"),
            verificationRef = rs.getString("verification_ref"),
            dispositionGeneration = rs.getInt("disposition_generation"),
          ),
        )
      }
      results
    }
  }

  override fun getNonRegressionConstraints(itemId: String): List<String> = connection.prepareStatement(
    """
      SELECT constraint_text
      FROM feature_task_audit_repair_non_regression
      WHERE item_id = ?
      ORDER BY priority ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, itemId)
    stmt.executeQuery().use { rs ->
      val constraints = mutableListOf<String>()
      while (rs.next()) {
        constraints.add(rs.getString("constraint_text"))
      }
      constraints
    }
  }

  override fun getGapDisposition(gapId: String): AuditGapDisposition? = connection.prepareStatement(
    """
      SELECT gap_id, status, evidence_observation, evidence_artifact_ref, evidence_check_ref,
             disposition_generation, superseded_by_generation
      FROM feature_task_audit_gap_dispositions
      WHERE gap_id = ?
      ORDER BY disposition_generation DESC
      LIMIT 1
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, gapId)
    stmt.executeQuery().use { rs ->
      if (rs.next()) {
        val evidence = FeatureTaskRuntimeEvidence(
          observation = FeatureTaskRuntimeEvidence.Observation.valueOf(rs.getString("evidence_observation")),
          artifactRef = rs.getString("evidence_artifact_ref"),
          checkRef = rs.getString("evidence_check_ref"),
        )
        AuditGapDisposition(
          gapId = rs.getString("gap_id"),
          status = AuditGapStatus.valueOf(rs.getString("status")),
          evidence = evidence,
          dispositionGeneration = rs.getInt("disposition_generation"),
          supersededByGeneration = rs.getInt("superseded_by_generation").takeIf { it > 0 },
        )
      } else {
        null
      }
    }
  }

  override fun getAllGapDispositions(workflowId: String): List<AuditGapDisposition> = connection.prepareStatement(
    """
      SELECT gd.gap_id, gd.status, gd.evidence_observation, gd.evidence_artifact_ref,
             gd.evidence_check_ref, gd.disposition_generation, gd.superseded_by_generation
      FROM feature_task_audit_gap_dispositions gd
      JOIN feature_task_audit_gaps g ON gd.gap_id = g.gap_id
      WHERE g.workflow_id = ?
      ORDER BY gd.disposition_generation ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      val dispositions = mutableListOf<AuditGapDisposition>()
      while (rs.next()) {
        val evidence = FeatureTaskRuntimeEvidence(
          observation = FeatureTaskRuntimeEvidence.Observation.valueOf(rs.getString("evidence_observation")),
          artifactRef = rs.getString("evidence_artifact_ref"),
          checkRef = rs.getString("evidence_check_ref"),
        )
        dispositions.add(
          AuditGapDisposition(
            gapId = rs.getString("gap_id"),
            status = AuditGapStatus.valueOf(rs.getString("status")),
            evidence = evidence,
            dispositionGeneration = rs.getInt("disposition_generation"),
            supersededByGeneration = rs.getInt("superseded_by_generation").takeIf { it > 0 },
          ),
        )
      }
      dispositions
    }
  }

  override fun getRecurringGaps(workflowId: String): List<AuditGap> = connection.prepareStatement(
    """
      SELECT gap_id, acceptance_criterion_ref, acceptance_criterion_text, diagnosis,
             affected_boundary, status, recurrence, first_seen_generation,
             failure_observation, failure_artifact_ref, failure_check_ref
      FROM feature_task_audit_gaps
      WHERE workflow_id = ? AND status = 'RECURRING'
      ORDER BY gap_id ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      val gaps = mutableListOf<AuditGap>()
      while (rs.next()) {
        val evidence = FeatureTaskRuntimeEvidence(
          observation = FeatureTaskRuntimeEvidence.Observation.valueOf(rs.getString("failure_observation")),
          artifactRef = rs.getString("failure_artifact_ref"),
          checkRef = rs.getString("failure_check_ref"),
        )
        gaps.add(
          AuditGap(
            gapId = rs.getString("gap_id"),
            acceptanceCriterionRef = rs.getString("acceptance_criterion_ref"),
            acceptanceCriterionText = rs.getString("acceptance_criterion_text"),
            failureEvidence = evidence,
            diagnosis = rs.getString("diagnosis"),
            affectedBoundary = rs.getString("affected_boundary"),
            status = AuditGapStatus.valueOf(rs.getString("status")),
            recurrence = rs.getInt("recurrence"),
            firstSeenGeneration = rs.getInt("first_seen_generation"),
          ),
        )
      }
      gaps
    }
  }

  override fun getResolvedGaps(workflowId: String): List<AuditGap> = connection.prepareStatement(
    """
      SELECT gap_id, acceptance_criterion_ref, acceptance_criterion_text, diagnosis,
             affected_boundary, status, recurrence, first_seen_generation,
             failure_observation, failure_artifact_ref, failure_check_ref
      FROM feature_task_audit_gaps
      WHERE workflow_id = ? AND status = 'RESOLVED'
      ORDER BY gap_id ASC
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.executeQuery().use { rs ->
      val gaps = mutableListOf<AuditGap>()
      while (rs.next()) {
        val evidence = FeatureTaskRuntimeEvidence(
          observation = FeatureTaskRuntimeEvidence.Observation.valueOf(rs.getString("failure_observation")),
          artifactRef = rs.getString("failure_artifact_ref"),
          checkRef = rs.getString("failure_check_ref"),
        )
        gaps.add(
          AuditGap(
            gapId = rs.getString("gap_id"),
            acceptanceCriterionRef = rs.getString("acceptance_criterion_ref"),
            acceptanceCriterionText = rs.getString("acceptance_criterion_text"),
            failureEvidence = evidence,
            diagnosis = rs.getString("diagnosis"),
            affectedBoundary = rs.getString("affected_boundary"),
            status = AuditGapStatus.valueOf(rs.getString("status")),
            recurrence = rs.getInt("recurrence"),
            firstSeenGeneration = rs.getInt("first_seen_generation"),
          ),
        )
      }
      gaps
    }
  }
}
