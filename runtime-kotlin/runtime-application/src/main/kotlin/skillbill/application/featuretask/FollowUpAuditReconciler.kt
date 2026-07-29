@file:Suppress("FunctionParameterNaming", "UnusedParameter", "UnusedPrivateProperty")

package skillbill.application.featuretask

import skillbill.application.model.FollowUpReconciliation
import skillbill.ports.persistence.AuditGenerationStore
import skillbill.ports.persistence.AuditRepairBatchStore
import skillbill.ports.persistence.AuditRepairQuery
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditGenerationIdentities
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.RepositoryCheckpoint
import java.time.Instant

class FollowUpAuditReconciler(
  private val generationStore: AuditGenerationStore,
  private val batchStore: AuditRepairBatchStore,
  private val repairQuery: AuditRepairQuery,
  private val identityResolver: AuditGapIdentityResolver,
  private val blastRadiusInspector: AuditBlastRadiusInspector,
  private val satisfactionGate: AuditSatisfactionGate,
) {
  fun reconcileFollowUp(
    workflowId: String,
    currentAudit: FeatureTaskRuntimeAuditRepairPlan,
    repositoryFingerprint: String,
  ): FollowUpReconciliation {
    val previousGeneration = generationStore.getLatest(workflowId)
      ?: return FollowUpReconciliation.NoPriorGeneration

    val newGeneration = previousGeneration.generation + 1
    val now = Instant.now().toString()

    val carriedGaps = previousGeneration.gaps.map { priorGap ->
      reconcileCarriedGap(workflowId, priorGap, currentAudit, previousGeneration.generation)
    }

    val newGaps = identifyNewGaps(currentAudit, previousGeneration, newGeneration)

    val blastRadiusGaps = blastRadiusInspector.inspectBlastRadius(
      previousGeneration = previousGeneration,
      currentAudit = currentAudit,
      newGeneration = newGeneration,
    )

    val allGaps = (carriedGaps + newGaps + blastRadiusGaps)
      .distinctBy { it.gapId }

    val repositoryCheckpoint = RepositoryCheckpoint(
      fingerprint = repositoryFingerprint,
      evidenceRef = "audit-followup-$newGeneration",
    )

    val followUpGeneration = AuditGeneration(
      generationId = AuditGenerationIdentities.generationId(workflowId, newGeneration),
      workflowId = workflowId,
      generation = newGeneration,
      repositoryCheckpoint = repositoryCheckpoint,
      satisfiedCriterionRefs = previousGeneration.satisfiedCriterionRefs,
      gaps = allGaps,
      repairBatch = null,
      createdAt = now,
    )

    val persisted = generationStore.persist(followUpGeneration)

    val canSatisfy = satisfactionGate.canSatisfy(persisted)

    return FollowUpReconciliation.Reconciled(
      generation = persisted,
      canSatisfy = canSatisfy,
    )
  }

  private fun reconcileCarriedGap(
    workflowId: String,
    priorGap: AuditGap,
    currentAudit: FeatureTaskRuntimeAuditRepairPlan,
    _previousGeneration: Int,
  ): AuditGap {
    val currentGap = currentAudit.gaps.firstOrNull { gap ->
      identityResolver.isSameIdentity(priorGap.gapId, gap.gapId)
    }

    return if (currentGap != null) {
      val disposition = repairQuery.getGapDisposition(workflowId, priorGap.gapId)
      if (disposition != null && disposition.status == AuditGapStatus.RESOLVED) {
        priorGap.copy(
          status = AuditGapStatus.RESOLVED,
          recurrence = priorGap.recurrence,
        )
      } else {
        priorGap.copy(
          status = AuditGapStatus.RECURRING,
          recurrence = priorGap.recurrence + 1,
        )
      }
    } else {
      priorGap.copy(
        status = AuditGapStatus.RESOLVED,
      )
    }
  }

  private fun identifyNewGaps(
    currentAudit: FeatureTaskRuntimeAuditRepairPlan,
    previousGeneration: AuditGeneration,
    newGeneration: Int,
  ): List<AuditGap> {
    val previousGapIds = previousGeneration.gaps.mapTo(linkedSetOf()) { it.gapId }

    return currentAudit.gaps.filterNot { planGap ->
      previousGapIds.any { priorId ->
        identityResolver.isSameIdentity(priorId, planGap.gapId)
      }
    }.map { planGap ->
      AuditGap(
        gapId = planGap.gapId,
        acceptanceCriterionRef = planGap.acceptanceCriterionRef,
        acceptanceCriterionText = planGap.acceptanceCriterionText,
        failureEvidence = planGap.failureEvidence,
        diagnosis = planGap.diagnosis,
        affectedBoundary = planGap.affectedBoundary,
        status = AuditGapStatus.NEW,
        recurrence = 0,
        firstSeenGeneration = newGeneration,
      )
    }
  }
}

class AuditGapIdentityResolver {
  fun isSameIdentity(gapId1: String, gapId2: String): Boolean = gapId1.equals(gapId2, ignoreCase = true)

  fun extractCriterionRef(gapId: String): String {
    val match = Regex("^([Aa][Cc]-[0-9]{3,})-gap-[0-9]+$").find(gapId)
      ?: throw IllegalArgumentException("Invalid gap_id format: $gapId")
    return match.groupValues[1].uppercase()
  }
}

class AuditBlastRadiusInspector {
  fun inspectBlastRadius(
    previousGeneration: AuditGeneration,
    currentAudit: FeatureTaskRuntimeAuditRepairPlan,
    newGeneration: Int,
  ): List<AuditGap> {
    val repairBatch = previousGeneration.repairBatch ?: return emptyList()

    val affectedPaths = repairBatch.repairItems.flatMap { item ->
      item.affectedPathsOrSymbols
    }.toSet()

    val newGapsInBlastRadius = currentAudit.gaps.filter { planGap ->
      planGap.affectedBoundary.split(",").any { boundary ->
        affectedPaths.any { path ->
          path.contains(boundary.trim()) || boundary.trim().contains(path)
        }
      }
    }

    return newGapsInBlastRadius.map { planGap ->
      AuditGap(
        gapId = planGap.gapId,
        acceptanceCriterionRef = planGap.acceptanceCriterionRef,
        acceptanceCriterionText = planGap.acceptanceCriterionText,
        failureEvidence = planGap.failureEvidence,
        diagnosis = planGap.diagnosis,
        affectedBoundary = planGap.affectedBoundary,
        status = AuditGapStatus.NEW,
        recurrence = 0,
        firstSeenGeneration = newGeneration,
      )
    }
  }
}

class AuditSatisfactionGate {
  fun canSatisfy(generation: AuditGeneration): Boolean {
    val hasUnresolvedGaps = generation.gaps.any { gap ->
      gap.status in setOf(AuditGapStatus.NEW, AuditGapStatus.RECURRING, AuditGapStatus.STILL_OPEN)
    }

    return !hasUnresolvedGaps
  }

  fun verifyEveryCarriedGapDisposed(previousGeneration: AuditGeneration, currentGeneration: AuditGeneration): Boolean {
    val previousGapIds = previousGeneration.gaps.mapTo(linkedSetOf()) { it.gapId }

    val allDisposed = previousGapIds.all { gapId ->
      val disposition = currentGeneration.gaps.firstOrNull { it.gapId == gapId }
      disposition != null && disposition.status in setOf(
        AuditGapStatus.RESOLVED,
        AuditGapStatus.SUPERSEDED,
      )
    }

    return allDisposed
  }
}
