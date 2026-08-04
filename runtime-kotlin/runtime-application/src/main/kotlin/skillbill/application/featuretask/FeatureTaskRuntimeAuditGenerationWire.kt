package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeAuditGenerationSchemaError
import skillbill.workflow.taskruntime.model.AUDIT_GENERATION_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGeneration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBlastRadiusInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCriterionInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCriterionInspectionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGenerationGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGovernanceEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairBatch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier

internal val AUDIT_GENERATION_KEYS = setOf(
  "contract_version",
  "generation_ordinal",
  "repository_checkpoint",
  "inspected_criteria",
  "satisfied_criterion_refs",
  "gaps",
  "repair_batch",
)
internal val AUDIT_GENERATION_OPTIONAL_KEYS = setOf("blast_radius_inspection")

/**
 * The audit produced-output keys that carry a carried-gap disposition, in precedence order. The expanded
 * envelope key comes first; `carried_gap_dispositions` is the one the mandated compact audit shape can emit
 * alongside `gaps`, so a follow-up audit can say a carried gap is resolved and back it with its own evidence.
 */
internal val FEATURE_TASK_RUNTIME_AUDIT_GAP_DISPOSITION_KEYS =
  listOf("prior_gap_dispositions", "carried_gap_dispositions")
internal val AUDIT_GENERATION_CHECKPOINT_KEYS = setOf("fingerprint")
internal val AUDIT_GENERATION_INSPECTION_KEYS = setOf("acceptance_criterion_ref", "inspection_verdict")
internal val AUDIT_GENERATION_GAP_KEYS = setOf(
  "gap_id",
  "acceptance_criterion_ref",
  "acceptance_criterion_text",
  "state",
  "recurrence_count",
  "failure_evidence",
  "diagnosis",
  "affected_boundary",
  "repair_item_ids",
)
internal val AUDIT_GENERATION_BATCH_KEYS = setOf("batch_id", "repair_item_ids", "repair_item_dispositions")
internal val AUDIT_GENERATION_DISPOSITION_KEYS = setOf("repair_item_id", "disposition", "result_evidence")
internal val AUDIT_GENERATION_DISPOSITION_OPTIONAL_KEYS = setOf("governance_evidence")
internal val AUDIT_GENERATION_GOVERNANCE_KEYS = setOf("governing_decision", "authority_ref", "rationale")
internal val AUDIT_GENERATION_BLAST_RADIUS_KEYS = setOf("inspected_paths", "newly_introduced_gap_ids", "evidence")

internal fun auditGenerationToWire(generation: FeatureTaskRuntimeAuditGeneration): Map<String, Any?> = buildMap {
  put("contract_version", generation.contractVersion)
  put("generation_ordinal", generation.generationOrdinal)
  put("repository_checkpoint", mapOf("fingerprint" to generation.repositoryCheckpoint.fingerprint))
  put(
    "inspected_criteria",
    generation.inspectedCriteria.map { inspection ->
      mapOf(
        "acceptance_criterion_ref" to inspection.acceptanceCriterionRef,
        "inspection_verdict" to inspection.inspectionVerdict.name.lowercase(),
      )
    },
  )
  put("satisfied_criterion_refs", generation.satisfiedCriterionRefs)
  put("gaps", generation.gaps.map(::generationGapToWire))
  put("repair_batch", repairBatchToWire(generation.repairBatch))
  generation.blastRadiusInspection?.let { put("blast_radius_inspection", blastRadiusToWire(it)) }
}

private fun generationGapToWire(gap: FeatureTaskRuntimeGenerationGap): Map<String, Any?> = mapOf(
  "gap_id" to gap.gapId,
  "acceptance_criterion_ref" to gap.acceptanceCriterionRef,
  "acceptance_criterion_text" to gap.acceptanceCriterionText,
  "state" to gap.state.name.lowercase(),
  "recurrence_count" to gap.recurrenceCount,
  "failure_evidence" to AuditEvidenceWire.toWire(gap.failureEvidence),
  "diagnosis" to gap.diagnosis,
  "affected_boundary" to gap.affectedBoundary,
  "repair_item_ids" to gap.repairItemIds,
)

private fun repairBatchToWire(batch: FeatureTaskRuntimeRepairBatch): Map<String, Any?> = mapOf(
  "batch_id" to batch.batchId,
  "repair_item_ids" to batch.repairItemIds,
  "repair_item_dispositions" to batch.repairItemDispositions.map { disposition ->
    buildMap<String, Any?> {
      put("repair_item_id", disposition.repairItemId)
      put("disposition", disposition.disposition.name.lowercase())
      put("result_evidence", AuditEvidenceWire.toWire(disposition.resultEvidence))
      disposition.governanceEvidence?.let { governance ->
        put(
          "governance_evidence",
          mapOf(
            "governing_decision" to governance.governingDecision,
            "authority_ref" to governance.authorityRef,
            "rationale" to governance.rationale,
          ),
        )
      }
    }
  },
)

private fun blastRadiusToWire(inspection: FeatureTaskRuntimeBlastRadiusInspection): Map<String, Any?> = mapOf(
  "inspected_paths" to inspection.inspectedPaths,
  "newly_introduced_gap_ids" to inspection.newlyIntroducedGapIds,
  "evidence" to AuditEvidenceWire.toWire(inspection.evidence),
)

internal fun auditGenerationFromWire(value: Any?, source: String): FeatureTaskRuntimeAuditGeneration =
  auditGenerationMapping(source) {
    val map = value.requiredMap(source)
    requireExactWireKeys(map, source, AUDIT_GENERATION_KEYS, AUDIT_GENERATION_OPTIONAL_KEYS)
    val contractVersion = map.requiredString("contract_version", source)
    if (contractVersion != AUDIT_GENERATION_CONTRACT_VERSION) {
      invalidWire("$source.contract_version", "must be '$AUDIT_GENERATION_CONTRACT_VERSION'")
    }
    val checkpointSource = "$source.repository_checkpoint"
    val checkpointMap = map["repository_checkpoint"].requiredMap(checkpointSource)
    requireExactWireKeys(checkpointMap, checkpointSource, AUDIT_GENERATION_CHECKPOINT_KEYS)
    FeatureTaskRuntimeAuditGeneration(
      generationOrdinal = map.requiredInt("generation_ordinal", source),
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(
        checkpointMap.requiredString("fingerprint", checkpointSource),
      ),
      inspectedCriteria = map.requiredList("inspected_criteria", source).mapIndexed { index, entry ->
        criterionInspectionFromWire(entry, "$source.inspected_criteria[$index]")
      },
      satisfiedCriterionRefs = map.stringList("satisfied_criterion_refs", source, required = true),
      gaps = map.requiredList("gaps", source).mapIndexed { index, entry ->
        generationGapFromWire(entry, "$source.gaps[$index]")
      },
      repairBatch = repairBatchFromWire(map["repair_batch"], "$source.repair_batch"),
      blastRadiusInspection = map["blast_radius_inspection"]?.let {
        blastRadiusFromWire(it, "$source.blast_radius_inspection")
      },
      contractVersion = contractVersion,
    )
  }

private fun criterionInspectionFromWire(value: Any?, source: String): FeatureTaskRuntimeCriterionInspection {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_GENERATION_INSPECTION_KEYS)
  return FeatureTaskRuntimeCriterionInspection(
    acceptanceCriterionRef = map.requiredString("acceptance_criterion_ref", source),
    inspectionVerdict = when (val verdict = map.requiredString("inspection_verdict", source)) {
      "satisfied" -> FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED
      "gap" -> FeatureTaskRuntimeCriterionInspectionVerdict.GAP
      else -> invalidWire("$source.inspection_verdict", "unauthorized verdict '$verdict'; must be satisfied or gap")
    },
  )
}

private fun generationGapFromWire(value: Any?, source: String): FeatureTaskRuntimeGenerationGap {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_GENERATION_GAP_KEYS)
  return FeatureTaskRuntimeGenerationGap(
    gapId = canonicalAuditIdentifier(map.requiredString("gap_id", source)),
    acceptanceCriterionRef = map.requiredString("acceptance_criterion_ref", source),
    acceptanceCriterionText = map.requiredString("acceptance_criterion_text", source),
    state = gapStateFromWire(map.requiredString("state", source), source),
    recurrenceCount = map.requiredInt("recurrence_count", source),
    failureEvidence = AuditEvidenceWire.fromWire(map["failure_evidence"], "$source.failure_evidence"),
    diagnosis = map.requiredString("diagnosis", source),
    affectedBoundary = map.requiredString("affected_boundary", source),
    repairItemIds = map.stringList("repair_item_ids", source, required = true).map(::canonicalAuditIdentifier),
  )
}

private fun gapStateFromWire(value: String, source: String): FeatureTaskRuntimeAuditGapState = when (value) {
  "new" -> FeatureTaskRuntimeAuditGapState.NEW
  "recurring" -> FeatureTaskRuntimeAuditGapState.RECURRING
  "resolved" -> FeatureTaskRuntimeAuditGapState.RESOLVED
  "superseded" -> FeatureTaskRuntimeAuditGapState.SUPERSEDED
  "still_open" -> FeatureTaskRuntimeAuditGapState.STILL_OPEN
  else -> invalidWire(
    "$source.state",
    "unauthorized gap state '$value'; must be one of new, recurring, resolved, superseded, still_open",
  )
}

private fun repairBatchFromWire(value: Any?, source: String): FeatureTaskRuntimeRepairBatch {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_GENERATION_BATCH_KEYS)
  return FeatureTaskRuntimeRepairBatch(
    batchId = map.requiredString("batch_id", source),
    repairItemIds = map.stringList("repair_item_ids", source, required = true).map(::canonicalAuditIdentifier),
    repairItemDispositions = map.requiredArray("repair_item_dispositions", source).mapIndexed { index, entry ->
      repairItemDispositionFromWire(entry, "$source.repair_item_dispositions[$index]")
    },
  )
}

private fun repairItemDispositionFromWire(value: Any?, source: String): FeatureTaskRuntimeRepairItemDisposition {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_GENERATION_DISPOSITION_KEYS, AUDIT_GENERATION_DISPOSITION_OPTIONAL_KEYS)
  return FeatureTaskRuntimeRepairItemDisposition(
    repairItemId = canonicalAuditIdentifier(map.requiredString("repair_item_id", source)),
    disposition = when (val disposition = map.requiredString("disposition", source)) {
      "fixed" -> FeatureTaskRuntimeRepairDisposition.FIXED
      "already_satisfied" -> FeatureTaskRuntimeRepairDisposition.ALREADY_SATISFIED
      "superseded" -> FeatureTaskRuntimeRepairDisposition.SUPERSEDED
      else -> invalidWire(
        "$source.disposition",
        "unauthorized disposition '$disposition'; must be one of fixed, already_satisfied, superseded",
      )
    },
    resultEvidence = AuditEvidenceWire.fromWire(map["result_evidence"], "$source.result_evidence"),
    governanceEvidence = map["governance_evidence"]?.let { governance ->
      val governanceSource = "$source.governance_evidence"
      val governanceMap = governance.requiredMap(governanceSource)
      requireExactWireKeys(governanceMap, governanceSource, AUDIT_GENERATION_GOVERNANCE_KEYS)
      FeatureTaskRuntimeGovernanceEvidence(
        governingDecision = governanceMap.requiredString("governing_decision", governanceSource),
        authorityRef = governanceMap.requiredString("authority_ref", governanceSource),
        rationale = governanceMap.requiredString("rationale", governanceSource),
      )
    },
  )
}

private fun blastRadiusFromWire(value: Any?, source: String): FeatureTaskRuntimeBlastRadiusInspection {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_GENERATION_BLAST_RADIUS_KEYS)
  return FeatureTaskRuntimeBlastRadiusInspection(
    inspectedPaths = map.stringList("inspected_paths", source, required = true),
    newlyIntroducedGapIds = map.stringList("newly_introduced_gap_ids", source, required = true)
      .map(::canonicalAuditIdentifier),
    evidence = AuditEvidenceWire.fromWire(map["evidence"], "$source.evidence"),
  )
}

internal val AUDIT_GENERATION_SUPERSESSION_KEYS = setOf(
  "repair_item_id",
  "governing_decision",
  "authority_ref",
  "rationale",
)

/**
 * Governed supersessions a repair receipt claims, keyed by repair item. Absent means every reported item is
 * an ordinary verified outcome; a supersession without these fields cannot be represented at all, which is
 * how the gate rejects an ungoverned one.
 */
internal fun supersededRepairItemsFrom(
  producedOutputs: Map<String, Any?>?,
): Map<String, FeatureTaskRuntimeGovernanceEvidence> {
  val entries = producedOutputs?.get("superseded_repair_items") as? List<*> ?: return emptyMap()
  return entries.mapIndexed { index, entry ->
    val source = "implement.superseded_repair_items[$index]"
    val map = entry.requiredMap(source)
    requireExactWireKeys(map, source, AUDIT_GENERATION_SUPERSESSION_KEYS)
    canonicalAuditIdentifier(map.requiredString("repair_item_id", source)) to FeatureTaskRuntimeGovernanceEvidence(
      governingDecision = map.requiredString("governing_decision", source),
      authorityRef = map.requiredString("authority_ref", source),
      rationale = map.requiredString("rationale", source),
    )
  }.toMap()
}

internal fun blastRadiusInspectionFrom(
  producedOutputs: Map<String, Any?>?,
  phaseId: String,
): FeatureTaskRuntimeBlastRadiusInspection? {
  if (phaseId != skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
  val value = producedOutputs?.get("blast_radius_inspection") ?: return null
  return auditGenerationMapping("audit.produced_outputs.blast_radius_inspection") {
    blastRadiusFromWire(value, "audit.produced_outputs.blast_radius_inspection")
  }
}

private inline fun <T> auditGenerationMapping(source: String, block: () -> T): T = try {
  block()
} catch (error: InvalidFeatureTaskRuntimeAuditGenerationSchemaError) {
  throw error
} catch (error: IllegalArgumentException) {
  throw InvalidFeatureTaskRuntimeAuditGenerationSchemaError(source, error.message.orEmpty(), error)
} catch (error: skillbill.error.InvalidWorkflowStateSchemaError) {
  throw InvalidFeatureTaskRuntimeAuditGenerationSchemaError(source, error.message.orEmpty(), error)
}
