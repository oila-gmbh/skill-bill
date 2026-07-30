@file:Suppress("MagicNumber", "TooManyFunctions")

package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.AuditGenerationStore
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.model.AuditRepairItemResult
import skillbill.workflow.taskruntime.model.CONVERGENCE_REFERENCE_MAX_LENGTH
import skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapDisposition
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditGenerationIdentities
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.ReplayResult
import skillbill.workflow.taskruntime.model.RepositoryCheckpoint
import java.sql.Connection
import java.sql.SQLException

class SQLiteAuditGenerationStore(
  private val connection: Connection,
  private val convergence: ConvergenceStateRepository = SQLiteConvergenceStateRepository(connection),
) : AuditGenerationStore {
  override fun persist(generation: AuditGeneration): AuditGeneration {
    val existing = getLatest(generation.workflowId)
    if (existing?.generation == generation.generation) {
      require(existing == generation) {
        "Conflicting replay for audit generation ${generation.generation} in workflow '${generation.workflowId}'."
      }
      appendCanonicalGeneration(existing)
      return existing
    }
    require(existing == null || existing.generation < generation.generation) {
      "Cannot persist generation ${generation.generation} when generation ${existing?.generation} already exists."
    }

    val priorAutoCommit = connection.autoCommit
    if (priorAutoCommit) connection.autoCommit = false
    try {
      connection.prepareStatement(
        """
      INSERT INTO feature_task_audit_generations(
        generation_id, workflow_id, generation, repository_fingerprint, repository_evidence_ref,
        created_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, generation) DO NOTHING
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
      persistRepairBatch(connection, generation.workflowId, generation.repairBatch)
      val replayed = getByGeneration(generation.workflowId, generation.generation)
      require(replayed == generation) {
        "Persisted audit generation ${generation.generation} did not replay as its complete aggregate."
      }
      appendCanonicalGeneration(generation)
      if (priorAutoCommit) connection.commit()
      return replayed
    } catch (error: SQLException) {
      if (priorAutoCommit) connection.rollback()
      throw error
    } catch (error: IllegalArgumentException) {
      if (priorAutoCommit) connection.rollback()
      throw error
    } finally {
      if (priorAutoCommit) connection.autoCommit = true
    }
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
        INSERT INTO feature_task_audit_gaps(
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

  private fun persistRepairBatch(connection: Connection, workflowId: String, batch: AuditRepairBatch?) {
    if (batch == null) return
    persistRepairBatchRow(connection, workflowId, batch)
    batch.repairItems.forEach { item ->
      persistRepairItem(connection, workflowId, item)
      persistRepairItemBatchMapping(connection, workflowId, batch.batchId, item.itemId)
      batch.dependencies[item.itemId].orEmpty().forEach { dependency ->
        persistRepairItemDependency(connection, workflowId, batch.batchId, item.itemId, dependency)
      }
    }
  }

  private fun persistRepairBatchRow(connection: Connection, workflowId: String, batch: AuditRepairBatch) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_audit_repair_batches(
        batch_id, workflow_id, generation_id, is_active
      ) VALUES (?, ?, ?, ?)
      ON CONFLICT(batch_id) DO NOTHING
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, batch.batchId)
      stmt.setString(2, workflowId)
      stmt.setString(3, batch.generationId)
      stmt.setBoolean(4, batch.isActive)
      stmt.executeUpdate()
    }
  }

  private fun persistRepairItem(connection: Connection, workflowId: String, item: AuditRepairItem) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_audit_repair_items(
        workflow_id, item_id, gap_id, intended_outcome, implementation_actions, affected_paths_or_symbols,
        required_verification, dependencies
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, item_id) DO NOTHING
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setString(2, item.itemId)
      stmt.setString(3, item.gapId)
      stmt.setString(4, item.intendedOutcome)
      stmt.setString(5, item.implementationActions.joinToString("\n"))
      stmt.setString(6, item.affectedPathsOrSymbols.joinToString("\n"))
      stmt.setString(7, item.requiredVerification.joinToString("\n"))
      stmt.setString(8, item.dependencies.joinToString("\n"))
      stmt.executeUpdate()
    }
  }

  private fun persistRepairItemBatchMapping(
    connection: Connection,
    workflowId: String,
    batchId: String,
    itemId: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_audit_repair_item_batch_mapping(workflow_id, batch_id, item_id)
      VALUES (?, ?, ?)
      ON CONFLICT(workflow_id, batch_id, item_id) DO NOTHING
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setString(2, batchId)
      stmt.setString(3, itemId)
      stmt.executeUpdate()
    }
  }

  private fun persistRepairItemDependency(
    connection: Connection,
    workflowId: String,
    batchId: String,
    itemId: String,
    dependency: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_audit_repair_item_dependencies(workflow_id, batch_id, item_id, depends_on_item_id)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(workflow_id, batch_id, item_id, depends_on_item_id) DO NOTHING
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setString(2, batchId)
      stmt.setString(3, itemId)
      stmt.setString(4, dependency)
      stmt.executeUpdate()
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
      FROM feature_task_audit_repair_items item
      JOIN feature_task_audit_repair_item_batch_mapping mapping
        ON mapping.workflow_id = item.workflow_id AND mapping.item_id = item.item_id
      JOIN feature_task_audit_repair_batches batch ON batch.batch_id = mapping.batch_id
      WHERE batch.generation_id = ?
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
      SELECT dependency.item_id, dependency.depends_on_item_id
      FROM feature_task_audit_repair_item_dependencies dependency
      JOIN feature_task_audit_repair_batches batch ON batch.batch_id = dependency.batch_id
      WHERE batch.generation_id = ?
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

  private fun appendCanonicalGeneration(generation: AuditGeneration) {
    appendCanonicalCheckpoint(generation)
    generation.gaps.forEach { gap ->
      val parentLogicalId = appendCanonicalGap(generation, gap)
      if (gap.status in setOf(AuditGapStatus.RESOLVED, AuditGapStatus.SUPERSEDED)) {
        appendCanonicalGapDisposition(generation, gap, parentLogicalId)
      }
    }
    generation.repairBatch?.repairItems.orEmpty().forEach { item ->
      appendCanonicalRepairItem(generation, item)
    }
  }

  private fun appendCanonicalCheckpoint(generation: AuditGeneration) {
    val logicalId = ConvergenceIdentities.logical(
      generation.workflowId,
      ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
      "audit:${generation.generationId}",
    )
    convergence.appendOrThrow(
      ConvergenceRecord(
        recordId = ConvergenceIdentities.record(
          generation.workflowId,
          ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
          logicalId,
          generation.generation,
        ),
        logicalId = logicalId,
        kind = ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
        provenance = ConvergenceProvenance(generation.workflowId, generation.generation, "audit"),
        evidenceDigest = generation.repositoryCheckpoint.fingerprint,
        createdAt = generation.createdAt,
        status = ConvergenceStatus.COMPLETED,
        summary = "audit generation ${generation.generation} repository checkpoint",
        evidenceRef = generation.repositoryCheckpoint.evidenceRef.take(CONVERGENCE_REFERENCE_MAX_LENGTH),
      ),
    )
  }

  private fun appendCanonicalGap(generation: AuditGeneration, gap: AuditGap): String {
    val logicalId = ConvergenceIdentities.logical(
      generation.workflowId,
      ConvergenceRecordKind.AUDIT_GAP,
      gap.gapId,
    )
    convergence.appendOrThrow(
      ConvergenceRecord(
        recordId = ConvergenceIdentities.record(
          generation.workflowId,
          ConvergenceRecordKind.AUDIT_GAP,
          logicalId,
          generation.generation,
        ),
        logicalId = logicalId,
        kind = ConvergenceRecordKind.AUDIT_GAP,
        provenance = ConvergenceProvenance(generation.workflowId, generation.generation, "audit"),
        evidenceDigest = ConvergenceIdentities.digest(
          listOf(
            gap.gapId,
            gap.acceptanceCriterionRef,
            gap.failureEvidence.artifactRef.orEmpty(),
            gap.failureEvidence.checkRef.orEmpty(),
            gap.diagnosis,
            gap.affectedBoundary,
          ).joinToString("|"),
        ),
        createdAt = generation.createdAt,
        status = ConvergenceStatus.OPEN,
        classification = gap.acceptanceCriterionRef.lowercase().replace('-', '_'),
        summary = gap.diagnosis.take(CONVERGENCE_SUMMARY_MAX_LENGTH),
        path = gap.affectedBoundary.take(CONVERGENCE_REFERENCE_MAX_LENGTH),
      ),
    )
    return logicalId
  }

  private fun appendCanonicalGapDisposition(
    generation: AuditGeneration,
    gap: AuditGap,
    parentLogicalId: String,
  ) {
    val logicalId = ConvergenceIdentities.logical(
      generation.workflowId,
      ConvergenceRecordKind.AUDIT_REPAIR,
      "${gap.gapId}:${gap.status.name}",
    )
    convergence.appendOrThrow(
      ConvergenceRecord(
        recordId = ConvergenceIdentities.record(
          generation.workflowId,
          ConvergenceRecordKind.AUDIT_REPAIR,
          logicalId,
          generation.generation,
        ),
        logicalId = logicalId,
        kind = ConvergenceRecordKind.AUDIT_REPAIR,
        provenance = ConvergenceProvenance(generation.workflowId, generation.generation, "audit"),
        evidenceDigest = ConvergenceIdentities.digest(
          "${gap.gapId}|${gap.status.name}|${gap.recurrence}|${gap.firstSeenGeneration}",
        ),
        createdAt = generation.createdAt,
        status = ConvergenceStatus.RESOLVED,
        classification = gap.status.name.lowercase(),
        summary = "audit gap ${gap.gapId} ${gap.status.name.lowercase()}",
        parentLogicalId = parentLogicalId,
      ),
    )
  }

  private fun appendCanonicalRepairItem(generation: AuditGeneration, item: AuditRepairItem) {
    val parentLogicalId = ConvergenceIdentities.logical(
      generation.workflowId,
      ConvergenceRecordKind.AUDIT_GAP,
      item.gapId,
    )
    val logicalId = ConvergenceIdentities.logical(
      generation.workflowId,
      ConvergenceRecordKind.AUDIT_REPAIR,
      item.itemId,
    )
    convergence.appendOrThrow(
      ConvergenceRecord(
        recordId = ConvergenceIdentities.record(
          generation.workflowId,
          ConvergenceRecordKind.AUDIT_REPAIR,
          logicalId,
          generation.generation,
        ),
        logicalId = logicalId,
        kind = ConvergenceRecordKind.AUDIT_REPAIR,
        provenance = ConvergenceProvenance(generation.workflowId, generation.generation, "audit"),
        evidenceDigest = ConvergenceIdentities.digest(
          listOf(
            item.itemId,
            item.gapId,
            item.intendedOutcome,
            item.implementationActions.joinToString("|"),
            item.requiredVerification.joinToString("|"),
          ).joinToString("|"),
        ),
        createdAt = generation.createdAt,
        status = ConvergenceStatus.OPEN,
        classification = "repair_item",
        summary = item.intendedOutcome.take(CONVERGENCE_SUMMARY_MAX_LENGTH),
        path = item.affectedPathsOrSymbols.firstOrNull()?.take(CONVERGENCE_REFERENCE_MAX_LENGTH),
        parentLogicalId = parentLogicalId,
      ),
    )
  }
}

private fun ConvergenceStateRepository.appendOrThrow(record: ConvergenceRecord) {
  if (append(record) is ReplayResult.Conflict) {
    throw ConvergenceReplayConflictException(record.recordId)
  }
}

class SQLiteAuditRepairBatchStore(
  private val connection: Connection,
) : AuditRepairBatchStore {
  override fun persist(batch: AuditRepairBatch): AuditRepairBatch {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_audit_repair_batches(batch_id, workflow_id, generation_id, is_active)
      SELECT ?, workflow_id, ?, ?
      FROM feature_task_audit_generations
      WHERE generation_id = ?
      ON CONFLICT(batch_id) DO NOTHING
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, batch.batchId)
      stmt.setString(2, batch.generationId)
      stmt.setBoolean(3, batch.isActive)
      stmt.setString(4, batch.generationId)
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
      ORDER BY ag.generation ASC
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
        val active = AuditRepairBatch(
          batchId = batchId,
          generationId = generationId,
          repairItems = items,
          dependencies = dependencies,
          isActive = isActive,
        )
        check(!rs.next()) { "Multiple active audit repair batches exist for workflow '$workflowId'." }
        active
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
    FROM feature_task_audit_repair_items item
    JOIN feature_task_audit_repair_item_batch_mapping mapping
      ON mapping.workflow_id = item.workflow_id AND mapping.item_id = item.item_id
    JOIN feature_task_audit_repair_batches batch ON batch.batch_id = mapping.batch_id
    WHERE batch.generation_id = ?
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
      SELECT dependency.item_id, dependency.depends_on_item_id
      FROM feature_task_audit_repair_item_dependencies dependency
      JOIN feature_task_audit_repair_batches batch ON batch.batch_id = dependency.batch_id
      WHERE batch.generation_id = ?
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
  override fun appendResult(workflowId: String, result: AuditRepairItemResult) {
    val resultId = ConvergenceIdentities.logical(
      workflowId,
      ConvergenceRecordKind.AUDIT_REPAIR,
      "${result.itemId}:${result.dispositionGeneration}:${result.outcome.name}",
    )
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO feature_task_audit_repair_item_results(
        result_id, item_id, workflow_id, outcome, evidence_ref, verification_ref,
        disposition_generation, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, resultId)
      statement.setString(2, result.itemId)
      statement.setString(3, workflowId)
      statement.setString(4, result.outcome.name)
      statement.setString(5, result.evidenceRef)
      statement.setString(6, result.verificationRef)
      statement.setInt(7, result.dispositionGeneration)
      statement.executeUpdate()
    }
    require(getPriorResults(workflowId, result.itemId).any { it == result }) {
      "Conflicting immutable audit repair result '${result.itemId}/${result.dispositionGeneration}'."
    }
  }

  override fun appendDisposition(workflowId: String, disposition: AuditGapDisposition) {
    val dispositionId = ConvergenceIdentities.logical(
      workflowId,
      ConvergenceRecordKind.AUDIT_REPAIR,
      "${disposition.gapId}:${disposition.dispositionGeneration}:${disposition.status.name}",
    )
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO feature_task_audit_gap_dispositions(
        disposition_id, workflow_id, gap_id, status, evidence_observation,
        evidence_artifact_ref, evidence_check_ref, disposition_generation, superseded_by_generation
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, dispositionId)
      statement.setString(2, workflowId)
      statement.setString(3, disposition.gapId)
      statement.setString(4, disposition.status.name)
      statement.setString(5, disposition.evidence.observation.name)
      statement.setString(6, disposition.evidence.artifactRef)
      statement.setString(7, disposition.evidence.checkRef)
      statement.setInt(8, disposition.dispositionGeneration)
      statement.setObject(9, disposition.supersededByGeneration)
      statement.executeUpdate()
    }
    require(getAllGapDispositions(workflowId).any { it == disposition }) {
      "Conflicting immutable audit gap disposition '${disposition.gapId}/${disposition.dispositionGeneration}'."
    }
  }

  override fun getUnresolvedRepairItems(workflowId: String): List<AuditRepairItem> = connection.prepareStatement(
    """
      SELECT ari.item_id, ari.gap_id, ari.intended_outcome, ari.implementation_actions,
             ari.affected_paths_or_symbols, ari.required_verification, ari.dependencies
      FROM feature_task_audit_repair_items ari
      JOIN feature_task_audit_repair_item_batch_mapping m
        ON ari.workflow_id = m.workflow_id AND ari.item_id = m.item_id
      JOIN feature_task_audit_repair_batches b ON m.batch_id = b.batch_id
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

  override fun getPriorResults(workflowId: String, itemId: String): List<AuditRepairItemResult> =
    connection.prepareStatement(
      """
      SELECT item_id, outcome, evidence_ref, verification_ref, disposition_generation
      FROM feature_task_audit_repair_item_results
      WHERE workflow_id = ? AND item_id = ?
      ORDER BY disposition_generation ASC
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setString(2, itemId)
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

  override fun getNonRegressionConstraints(workflowId: String, itemId: String): List<String> =
    connection.prepareStatement(
      """
      SELECT constraint_text
      FROM feature_task_audit_repair_non_regression
      WHERE workflow_id = ? AND item_id = ?
      ORDER BY priority ASC
      """.trimIndent(),
    ).use { stmt ->
      stmt.setString(1, workflowId)
      stmt.setString(2, itemId)
      stmt.executeQuery().use { rs ->
        val constraints = mutableListOf<String>()
        while (rs.next()) {
          constraints.add(rs.getString("constraint_text"))
        }
        constraints
      }
    }

  override fun getGapDisposition(workflowId: String, gapId: String): AuditGapDisposition? = connection.prepareStatement(
    """
      SELECT gap_id, status, evidence_observation, evidence_artifact_ref, evidence_check_ref,
             disposition_generation, superseded_by_generation
      FROM feature_task_audit_gap_dispositions
      WHERE workflow_id = ? AND gap_id = ?
      ORDER BY disposition_generation DESC
      LIMIT 1
    """.trimIndent(),
  ).use { stmt ->
    stmt.setString(1, workflowId)
    stmt.setString(2, gapId)
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
      WHERE gd.workflow_id = ?
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
