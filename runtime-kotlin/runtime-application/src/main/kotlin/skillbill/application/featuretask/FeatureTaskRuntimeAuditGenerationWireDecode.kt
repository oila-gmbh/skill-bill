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

/**
 * Decode half of the audit-generation wire contract. Every durable generation read passes through here, so
 * each field is required explicitly and an unknown key is rejected rather than dropped: a generation that
 * decoded leniently would let a later write rewrite gap text the append-only history is supposed to pin.
 */
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
      // Present but possibly empty, at both fields: the zero-gap settlement that makes first-pass
      // convergence durable has no gaps, and an audit whose every criterion failed has nothing satisfied.
      // Requiring content here quarantined those generations on the next read, erasing valid history.
      satisfiedCriterionRefs = map.stringList("satisfied_criterion_refs", source),
      gaps = map.requiredArray("gaps", source).mapIndexed { index, entry ->
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
    repairItemIds = map.stringList("repair_item_ids", source).map(::canonicalAuditIdentifier),
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
    repairItemIds = map.stringList("repair_item_ids", source).map(::canonicalAuditIdentifier),
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

internal fun blastRadiusFromWire(value: Any?, source: String): FeatureTaskRuntimeBlastRadiusInspection {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_GENERATION_BLAST_RADIUS_KEYS)
  return FeatureTaskRuntimeBlastRadiusInspection(
    inspectedPaths = map.stringList("inspected_paths", source, required = true),
    // A repair batch that introduced nothing is the expected clean inspection, not a malformed record.
    newlyIntroducedGapIds = map.stringList("newly_introduced_gap_ids", source).map(::canonicalAuditIdentifier),
    evidence = AuditEvidenceWire.fromWire(map["evidence"], "$source.evidence"),
  )
}

internal inline fun <T> auditGenerationMapping(source: String, block: () -> T): T = try {
  block()
} catch (error: InvalidFeatureTaskRuntimeAuditGenerationSchemaError) {
  throw error
} catch (error: IllegalArgumentException) {
  throw InvalidFeatureTaskRuntimeAuditGenerationSchemaError(source, error.message.orEmpty(), error)
} catch (error: skillbill.error.InvalidWorkflowStateSchemaError) {
  throw InvalidFeatureTaskRuntimeAuditGenerationSchemaError(source, error.message.orEmpty(), error)
}
