package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.review.ReviewFindingActionability
import skillbill.review.ReviewFindingFieldCodec
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeExecutablePlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope

/**
 * Builds the delivered handoff envelope from static declarations, rejecting rather than repairing.
 *
 * Budgets are counted in UTF-8 bytes and collection items *before* the envelope exists, so an
 * oversized projection can never be truncated, silently dropped, or swapped for its full source
 * artifact — it fails the launch with a typed error naming the projection and its contract.
 */
@Suppress("TooManyFunctions", "LargeClass")
object FeatureTaskRuntimeHandoffProjectionValidator {
  const val COMPACT_REFERENCE_MAX_LENGTH: Int = 512

  @Suppress("ThrowsCount")
  fun validate(inputs: FeatureTaskRuntimeHandoffProjectionInputs): FeatureTaskRuntimeHandoffEnvelope {
    rejectDuplicateProjectionNames(inputs)
    val projections = inputs.declarations.mapNotNull { declaration ->
      requireSameConsumer(inputs, declaration)
      requireSupportedContractVersion(inputs, declaration)
      val resolved = resolveFields(inputs, declaration)
      val fields = enforceCheckpointPolicy(inputs, declaration, resolved.orEmpty())
      if (resolved == null) return@mapNotNull null
      enforceDeclaredShape(inputs, declaration, fields)
      enforceCompactReferences(inputs, declaration, fields)
      val projection = FeatureTaskRuntimeHandoffProjection(
        projectionName = declaration.projectionName,
        sourceRef = declaration.sourceRef,
        projectionContractId = declaration.projectionContractId,
        projectionContractVersion = declaration.projectionContractVersion,
        promptVisibility = declaration.promptVisibility,
        fields = fields,
        producerIteration = resolvedProducerIteration(inputs, declaration),
      )
      enforceBudget(inputs, declaration, projection)
      projection
    }
    return FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = inputs.consumerPhaseId,
      projections = projections,
      repositoryCheckpoint = inputs.resolvedCheckpoint,
    )
  }

  private fun resolvedProducerIteration(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeProducerIteration = when (val source = declaration.sourceRef) {
    is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput -> {
      val output = inputs.resolvedUpstream.outputsByPhaseId[source.producingPhaseId]
      if (output == null) {
        declaration.producerIteration
      } else {
        FeatureTaskRuntimeProducerIteration(source.producingPhaseId, output.iteration)
      }
    }
    else -> declaration.producerIteration
  }

  private fun rejectDuplicateProjectionNames(inputs: FeatureTaskRuntimeHandoffProjectionInputs) {
    val seen = mutableSetOf<String>()
    inputs.declarations.forEach { declaration ->
      if (!seen.add(declaration.projectionName)) {
        reject(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.DUPLICATE_PROJECTION_NAME,
          "the consumer phase declares this projection name more than once.",
        )
      }
    }
  }

  private fun requireSameConsumer(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ) {
    if (declaration.consumerPhaseId != inputs.consumerPhaseId) {
      reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "the declaration belongs to consumer phase '${declaration.consumerPhaseId}'.",
      )
    }
  }

  private fun requireSupportedContractVersion(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ) {
    if (declaration.projectionContractVersion !in SUPPORTED_PROJECTION_CONTRACT_VERSIONS) {
      reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.UNSUPPORTED_CONTRACT_VERSION,
        "supported versions are ${SUPPORTED_PROJECTION_CONTRACT_VERSIONS.joinToString()}.",
      )
    }
  }

  /**
   * Applies the declared checkpoint policy and returns the fields the consumer actually receives.
   *
   * The fingerprint a receipt carries is authored by the producing agent and is not comparable to the
   * runtime's own: the resolved value is a content hash over HEAD, the staged/unstaged diffs, and
   * untracked contents, while the carried value is whatever string the agent wrote. Comparing them
   * would reject or "refresh" on producer phrasing rather than on repository movement, so the carried
   * value is treated as an opaque claim throughout.
   *
   * `must_match` is retained as a legacy durable wire value. Like `refresh_from_repository`, it
   * requires and substitutes a freshly resolved checkpoint without rejecting repository movement.
   */
  private fun enforceCheckpointPolicy(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ): List<FeatureTaskRuntimeHandoffProjectionField> {
    val carried = if (
      declaration.projectionContractId ==
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE
    ) {
      null
    } else {
      receiptCarriedCheckpointFingerprint(fields)
    }
    checkpointPolicyViolation(inputs, declaration)?.let { violation ->
      reject(inputs, declaration, FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION, violation)
    }
    if (declaration.checkpointPolicy == FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED) {
      return fields
    }
    val resolvedFingerprint = inputs.resolvedCheckpoint?.fingerprint ?: return fields
    return fields.map { field ->
      resolvedCheckpointField(field, resolvedFingerprint, carried)
    }
  }

  private fun checkpointPolicyViolation(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): String? = when (declaration.checkpointPolicy) {
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED -> null
    FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY ->
      if (inputs.resolvedCheckpoint == null) {
        "checkpoint-aware policy requires a freshly resolved repository checkpoint, none was supplied."
      } else {
        null
      }
    FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH ->
      if (inputs.resolvedCheckpoint == null) {
        "must_match requires a freshly resolved repository checkpoint, none was supplied."
      } else {
        null
      }
  }

  private fun resolvedCheckpointField(
    field: FeatureTaskRuntimeHandoffProjectionField,
    resolvedFingerprint: String,
    carriedFingerprint: String?,
  ): FeatureTaskRuntimeHandoffProjectionField =
    if (field.name == FeatureTaskRuntimeImplementationReceipt.FIELD_REPOSITORY_CHECKPOINT) {
      field.copy(
        value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
          kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
          value = resolvedFingerprint +
            (carriedFingerprint?.let { CHECKPOINT_PRODUCER_CLAIM_SEPARATOR + it }.orEmpty()),
        ),
      )
    } else {
      field
    }

  // Re-projecting an already-substituted field must keep the producer's original claim rather than
  // promote the runtime fingerprint written over it, so an appended claim wins over the whole value.
  private fun receiptCarriedCheckpointFingerprint(fields: List<FeatureTaskRuntimeHandoffProjectionField>): String? =
    fields.firstOrNull { it.name == FeatureTaskRuntimeImplementationReceipt.FIELD_REPOSITORY_CHECKPOINT }
      ?.value
      ?.let { it as? FeatureTaskRuntimeHandoffProjectionValue.CompactReference }
      ?.takeIf { it.kind == FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT }
      ?.value
      ?.substringAfter(CHECKPOINT_PRODUCER_CLAIM_SEPARATOR)
      ?.takeIf(String::isNotBlank)

  // Returns null when a non-required source has no recorded value, so an optional projection is
  // omitted rather than delivered empty. A required source with no value is a hard rejection.
  private fun resolveFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    val fields = fieldsFor(inputs, declaration)
    if (fields == null && declaration.required) {
      reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MISSING_REQUIRED_SOURCE,
        "declared source '${declaration.sourceRef.wireValue}' has no recorded value.",
      )
    }
    return fields
  }

  private fun fieldsFor(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? = when (val sourceRef = declaration.sourceRef) {
    is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput ->
      inputs.resolvedUpstream.outputsByPhaseId[sourceRef.producingPhaseId]?.let { output ->
        planningProjectionFields(inputs, declaration, sourceRef.producingPhaseId, output)
          ?: phaseProjectionFields(inputs, declaration, output)
          ?: listOf(
            FeatureTaskRuntimeHandoffProjectionField(
              name = PHASE_OUTPUT_RECEIPT_FIELD,
              value = declaration.inlineAlternative?.let { kind ->
                FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
                  kind = kind,
                  value = privateEvidenceReference(sourceRef.producingPhaseId, output.iteration),
                )
              } ?: FeatureTaskRuntimeHandoffProjectionValue.Text(output.payload),
            ),
          )
      } ?: durableAuditRepairProjectionFields(inputs, declaration)
    is FeatureTaskRuntimeHandoffSourceRef.RunInvariantField ->
      runInvariantFields(inputs.runInvariants, sourceRef.invariantField)
    FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling -> listOf(
      FeatureTaskRuntimeHandoffProjectionField(
        name = CEREMONY_SCALING_FIELD,
        value = FeatureTaskRuntimeHandoffProjectionValue.TextList(
          FeatureTaskRuntimePhaseWorkflowDefinition
            .ceremonyScaling(inputs.runInvariants.featureSize)
            .toBriefingLines(),
        ),
      ),
    )
    FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence ->
      inputs.sharedReviewEvidence?.toProjectionFields()
    FeatureTaskRuntimeHandoffSourceRef.RepairLedger -> repairLedgerFields(inputs)
    is FeatureTaskRuntimeHandoffSourceRef.AddonContentRef ->
      inputs.addonContentBySlug[sourceRef.slug]?.let { content ->
        listOf(
          FeatureTaskRuntimeHandoffProjectionField(
            name = ADDON_CONTENT_FIELD,
            value = FeatureTaskRuntimeHandoffProjectionValue.Text(content),
          ),
        )
      }
  }

  private fun repairLedgerFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? = inputs.repairLedger
    ?.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
    ?.let { ledger ->
      listOf(
        FeatureTaskRuntimeHandoffProjectionField(
          name = FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME,
          value = FeatureTaskRuntimeHandoffProjectionValue.Text(
            JsonSupport.mapToJsonString(ledger.boundedProjection().toProjectionMap()),
          ),
        ),
      )
    }

  private fun durableAuditRepairProjectionFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    if (
      declaration.projectionContractId !=
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_REPAIR_REQUEST ||
      inputs.auditRepairPlan == null ||
      inputs.auditRepairState == null
    ) {
      return null
    }
    val runtimeOwned = runtimeOwnedPhaseProjectionValues(inputs, declaration, emptyMap())
    return declaration.declaredFieldNames.mapNotNull { name ->
      runtimeOwned[name]?.let { value ->
        FeatureTaskRuntimeHandoffProjectionField(name, projectionValue(name, value, inputs, declaration))
      }
    }
  }

  private fun runInvariantFields(
    runInvariants: FeatureTaskRuntimeRunInvariants,
    field: FeatureTaskRuntimeRunInvariantPromptField,
  ): List<FeatureTaskRuntimeHandoffProjectionField> {
    val value = when (field) {
      FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.specReference)
      FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.featureSize.name)
      FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(runInvariants.acceptanceCriteria)
      FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(runInvariants.mandatesAndOverrides)
      FeatureTaskRuntimeRunInvariantPromptField.REVIEW_POLICY ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.codeReviewMode.name)
      FeatureTaskRuntimeRunInvariantPromptField.AGENT_ADDONS ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(
          runInvariants.agentAddonSelection.entries.map { it.slug },
        )
      FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING,
      FeatureTaskRuntimeRunInvariantPromptField.FINALIZATION_CONTEXT,
      -> FeatureTaskRuntimeHandoffProjectionValue.TextList(emptyList())
    }
    return listOf(FeatureTaskRuntimeHandoffProjectionField(name = field.wireValue, value = value))
  }

  private fun enforceDeclaredShape(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ) {
    val seen = mutableSetOf<String>()
    fields.forEach { field ->
      if (field.name !in declaration.declaredFieldNames ||
        field.name in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
      ) {
        reject(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD,
          "field '${field.name}' is not part of the declared projection shape.",
        )
      }
      if (!seen.add(field.name)) {
        reject(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "field '${field.name}' appears more than once.",
        )
      }
    }
    declaration.declaredFieldNames.forEach { declaredName ->
      if (declaredName !in seen && declaration.required) {
        reject(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "declared field '$declaredName' resolved to no value.",
        )
      }
    }
  }

  private fun enforceCompactReferences(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ) {
    fields.forEach { field ->
      val reference = field.value as? FeatureTaskRuntimeHandoffProjectionValue.CompactReference ?: return@forEach
      val problem = when {
        reference.value.length > COMPACT_REFERENCE_MAX_LENGTH ->
          "reference in field '${field.name}' exceeds $COMPACT_REFERENCE_MAX_LENGTH characters; a compact " +
            "reference must be an identifier, not an inlined body."
        reference.value.any { it == '\n' || it == '\r' } ->
          "reference in field '${field.name}' contains a line break; a compact reference must be a single token."
        referencesPrivateEvidence(reference.value) && !declaration.allowsPrivateArtifactReference ->
          "field '${field.name}' references a private evidence artifact, but this projection does not declare a " +
            "runtime-owned deterministic inspection operation for it."
        else -> null
      }
      if (problem != null) {
        reject(inputs, declaration, FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE, problem)
      }
    }
  }

  private fun enforceBudget(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    projection: FeatureTaskRuntimeHandoffProjection,
  ) {
    val byteSize = projection.utf8ByteSize
    if (byteSize > declaration.budget.maxUtf8Bytes) {
      reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        "projection is $byteSize UTF-8 bytes against a ${declaration.budget.maxUtf8Bytes}-byte budget; " +
          "the runtime rejects rather than truncating or substituting the full source artifact.",
      )
    }
    val itemCount = projection.itemCount
    if (itemCount > declaration.budget.maxCollectionItems) {
      reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        "projection carries $itemCount items against a ${declaration.budget.maxCollectionItems}-item budget; " +
          "the runtime rejects rather than dropping items.",
      )
    }
  }

  private fun reject(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind,
    reason: String,
  ): Nothing = throw InvalidFeatureTaskRuntimeHandoffProjectionError(
    workflowId = inputs.workflowId,
    consumerPhaseId = inputs.consumerPhaseId,
    projectionName = declaration.projectionName,
    projectionContractId = declaration.projectionContractId,
    projectionContractVersion = declaration.projectionContractVersion,
    failureKind = failureKind,
    reason = reason,
  )

  /**
   * Deterministic locator for one private-evidence artifact: the phase-records store, the producing
   * phase, and the iteration. A consumer resolves it through the runtime's record lookup, so nothing
   * here grants a model an open retrieval capability.
   */
  fun privateEvidenceReference(producingPhaseId: String, iteration: Int): String =
    PRIVATE_EVIDENCE_LOCATOR_PREFIX + "$producingPhaseId#$iteration"

  private fun referencesPrivateEvidence(referenceValue: String): Boolean =
    referenceValue.startsWith(PRIVATE_EVIDENCE_LOCATOR_PREFIX)

  /**
   * Joins the authoritative runtime fingerprint to the producer's own checkpoint claim. The two are
   * not comparable values — see `enforceCheckpointPolicy` — so the claim is carried as provenance,
   * not as a superseded fingerprint. Single-token, so the field stays a compact reference.
   */
  const val CHECKPOINT_PRODUCER_CLAIM_SEPARATOR: String = "+producer-claimed:"

  const val PRIVATE_EVIDENCE_LOCATOR_PREFIX: String = "$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY/"
  const val PHASE_OUTPUT_RECEIPT_FIELD: String = "phase_output_receipt"
  const val CEREMONY_SCALING_FIELD: String = "ceremony_scaling"
  const val ADDON_CONTENT_FIELD: String = "addon_content"

  private val SUPPORTED_PROJECTION_CONTRACT_VERSIONS: Set<String> = setOf("0.1")

  private val PLANNING_PROJECTION_CONTRACT_IDS: Set<String> = setOf(
    FeatureTaskRuntimePlanningProjectionContract.PREPLANNING_DIGEST_ID,
    FeatureTaskRuntimePlanningProjectionContract.EXECUTABLE_PLAN_ID,
    FeatureTaskRuntimePlanningProjectionContract.PLAN_COMMITMENT_ID,
    FeatureTaskRuntimePlanningProjectionContract.IMPLEMENTATION_RECEIPT_ID,
  )

  private val PHASE_PROJECTION_CONTRACT_IDS: Set<String> = setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_REPAIR_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_CLEARANCE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
  )

  /**
   * Selects named values from the validated phase envelope. The declaration is the allowlist:
   * summary, narration, raw output, reports, progress and telemetry have no route into this method.
   * Structured list entries are independently serialized so collection budgets count every item.
   */
  private fun phaseProjectionFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    output: FeatureTaskRuntimePhaseOutput,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    if (declaration.projectionContractId !in PHASE_PROJECTION_CONTRACT_IDS) return null
    val envelope = output.normalizedOutput?.envelope
      ?: JsonSupport.parseObjectOrNull(output.payload)?.let { JsonSupport.jsonElementToValue(it) }
        ?.let(JsonSupport::anyToStringAnyMap)
      ?: reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "validated producer output could not be decoded as an object.",
      )
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
    val runtimeOwned = runtimeOwnedPhaseProjectionValues(inputs, declaration, produced)
    return declaration.declaredFieldNames.mapNotNull { name ->
      val value = runtimeOwned[name] ?: if (name == "verdict") {
        envelope[name]
      } else {
        resolveDeclaredPhaseField(produced, name)
      }
      value?.let {
        FeatureTaskRuntimeHandoffProjectionField(name, projectionValue(name, it, inputs, declaration))
      }
    }
  }

  private fun runtimeOwnedPhaseProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    produced: Map<String, Any?>,
  ): Map<String, Any?> = when (declaration.projectionContractId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE -> mapOf(
      "clearance_status" to auditClearanceStatus(produced),
      "review_scope" to FeatureTaskRuntimePhaseWorkflowDefinition
        .ceremonyScaling(inputs.runInvariants.featureSize)
        .reviewScope
        .wireValue,
      "repository_checkpoint" to inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) },
      "verdict" to auditClearanceStatus(produced),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_REPAIR_REQUEST -> {
      val plan = inputs.auditRepairPlan
      val state = inputs.auditRepairState
      mapOf(
        "audit_repair_plan" to plan?.let(::auditRepairPlanProjection),
        "prior_terminal_repair_outcomes" to state?.repairItemResults?.map { result ->
          mapOf(
            "repair_item_id" to result.repairItemId,
            "outcome" to result.outcome.name.lowercase(),
          )
        }.orEmpty(),
        "unresolved_gap_ids" to state?.unresolvedGapLedger?.unresolvedGaps?.map { it.gapId }
          .orEmpty()
          .ifEmpty { plan?.gaps?.map { it.gapId }.orEmpty() },
        "repository_checkpoint" to inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) },
      )
    }
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST -> mapOf(
      "unresolved_blocker_findings" to reviewBlockerProjection(produced, inputs.recordedFindingVerdicts),
      "repository_checkpoint" to inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) },
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT -> mapOf(
      "changed_paths" to inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty(),
      "tests_added" to (produced["tests_added"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "tests_updated" to (produced["tests_updated"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "deviations" to (produced["deviations"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "repository_checkpoint" to inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) },
    )
    else -> finalizationProjectionValues(inputs, declaration)
  }.filterValues { it != null }

  private fun auditClearanceStatus(produced: Map<String, Any?>): String? {
    val unmetCriteria = produced["unmet_criteria"] as? List<*>
    return when {
      unmetCriteria == null -> resolveDeclaredPhaseField(produced, "clearance_status")?.toString()
      unmetCriteria.isEmpty() -> FeatureTaskRuntimeVerdict.SATISFIED.wireValue
      else -> FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue
    }
  }

  private fun reviewBlockerProjection(
    produced: Map<String, Any?>,
    recordedFindingVerdicts: List<ReviewFindingVerdict>,
  ): List<Map<String, Any?>> = (produced["findings"] as? List<*>).orEmpty()
    .mapNotNull(JsonSupport::anyToStringAnyMap)
    .filter { finding ->
      val overlay = ReviewFindingActionability.overlayOf(
        findingRef = ReviewFindingFieldCodec.findingRefOf(
          finding["id"],
          finding["finding_id"],
          finding["f_number"],
        ),
        recordedVerdicts = recordedFindingVerdicts,
        encoded = ReviewFindingFieldCodec.recordedFieldsOf(
          claimVerdict = finding["claim_verdict"],
          scopeDisposition = finding["scope_disposition"],
          citations = finding["citations"],
          severityAdjustment = finding["severity_adjustment"],
        ),
      )
      ReviewFindingActionability.isActionable(overlay.claimVerdict, overlay.scopeDisposition)
    }
    .map { finding ->
      val severity = (finding["severity"] as? String)?.takeIf(String::isNotBlank) ?: "blocker"
      mapOf(
        "finding_id" to (finding["finding_id"] ?: finding["f_number"] ?: finding["id"]),
        "severity" to severity,
        "location" to (
          finding["location"] ?: finding["repository_path"] ?: finding["path"] ?: "repository"
          ),
        "expected_outcome" to (
          finding["expected_outcome"] ?: finding["message"] ?: finding["description"] ?: "Resolve the finding."
          ),
        "criterion_refs" to (finding["criterion_refs"] ?: emptyList<String>()),
        "task_refs" to (finding["task_refs"] ?: emptyList<String>()),
      ).filterValues { it != null }
    }

  private fun auditRepairPlanProjection(
    plan: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan,
  ): Map<String, Any?> = mapOf(
    "contract_version" to plan.contractVersion,
    "gaps" to plan.gaps.map { gap ->
      mapOf(
        "gap_id" to gap.gapId,
        "acceptance_criterion_ref" to gap.acceptanceCriterionRef,
        "acceptance_criterion_text" to gap.acceptanceCriterionText,
        "diagnosis" to gap.diagnosis,
        "affected_boundary" to gap.affectedBoundary,
        "repair_items" to gap.repairItems.map { item ->
          mapOf(
            "repair_item_id" to item.repairItemId,
            "intended_outcome" to item.intendedOutcome,
            "implementation_actions" to item.implementationActions,
            "affected_paths_or_symbols" to item.affectedPathsOrSymbols,
            "required_verification" to item.requiredVerification,
            "depends_on" to item.dependsOn,
            "status" to item.status.name.lowercase(),
          )
        },
      )
    },
  )

  private fun finalizationProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): Map<String, Any?> {
    val context = finalizationProjectionContext(inputs)
    return when (declaration.projectionContractId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST -> mapOf(
        "validation_strategy" to (context.plan["validation_strategy"] ?: emptyList<String>()),
        "changed_paths" to context.changedPaths,
        "required_checks" to context.requiredChecks,
        "repository_checkpoint" to context.checkpoint,
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES -> mapOf(
        "changed_paths" to context.changedPaths,
        "boundary_candidates" to context.changedPaths
          .map { it.substringBeforeLast('/', "") }
          .filter(String::isNotBlank)
          .distinct(),
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST -> mapOf(
        "path_inventory" to context.changedPaths,
        "required_inclusions" to context.changedPaths,
        "required_exclusions" to context.excludedClaims,
        "branch_identity" to context.branch,
        "gate_attestations" to listOf("audit", "review", "validate", "write_history"),
        "repository_checkpoint" to context.checkpoint,
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST ->
        prRequestProjection(context)
      else -> emptyMap()
    }
  }

  private fun finalizationProjectionContext(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): FinalizationProjectionContext {
    val outputs = inputs.resolvedUpstream.outputsByPhaseId
    val plan = outputs[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN]?.let {
      planningProducedOutputs(it)
    }.orEmpty()
    val implementation = outputs[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT]?.let {
      planningProducedOutputs(it)
    }.orEmpty()
    val validation = outputs[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE]?.let {
      genericProducedOutputs(it)
    }.orEmpty()
    val checkpoint = inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) }
    val claimedChangedPaths = (implementation["changed_paths"] as? List<*>).orEmpty()
      .filterIsInstance<String>()
      .distinct()
    val changedPaths = inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty()
      .distinct()
      .sorted()
    val excludedClaims = claimedChangedPaths.filterNot { it in changedPaths }.sorted()
    val requiredChecks = (
      (plan["validation_strategy"] as? List<*>).orEmpty() +
        (plan["tasks"] as? List<*>).orEmpty().flatMap { task ->
          JsonSupport.anyToStringAnyMap(task)?.get("test_obligations") as? List<*> ?: emptyList<Any?>()
        }
      ).filterIsInstance<String>().distinct()
    return FinalizationProjectionContext(
      plan = plan,
      implementation = implementation,
      validation = validation,
      checkpoint = checkpoint,
      changedPaths = changedPaths,
      excludedClaims = excludedClaims,
      requiredChecks = requiredChecks,
      branch = inputs.branchIdentity ?: "unknown",
      base = inputs.baseBranch,
      checkpointFingerprint = inputs.resolvedCheckpoint?.fingerprint,
    )
  }

  private fun prRequestProjection(context: FinalizationProjectionContext): Map<String, Any?> = mapOf(
    "completed_task_ids" to (context.implementation["completed_task_ids"] ?: emptyList<String>()),
    "changed_paths" to context.changedPaths,
    "tests_added" to (context.implementation["tests_added"] ?: emptyList<String>()),
    "tests_updated" to (context.implementation["tests_updated"] ?: emptyList<String>()),
    "deviations" to (context.implementation["deviations"] ?: emptyList<String>()),
    "validation_summary" to (
      context.validation["validation_result"]
        ?: context.validation["validation_summary"]
        ?: context.validation["summary"]
        ?: "completed"
      ),
    "base_branch" to context.base,
    "diff_reference" to (context.checkpointFingerprint ?: "repository-checkpoint-unavailable"),
  )

  private data class FinalizationProjectionContext(
    val plan: Map<String, Any?>,
    val implementation: Map<String, Any?>,
    val validation: Map<String, Any?>,
    val checkpoint: Map<String, String>?,
    val changedPaths: List<String>,
    val excludedClaims: List<String>,
    val requiredChecks: List<String>,
    val branch: String,
    val base: String,
    val checkpointFingerprint: String?,
  )

  private fun planningProducedOutputs(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?> =
    genericProducedOutputs(output)

  private fun genericProducedOutputs(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?> {
    val envelope = output.normalizedOutput?.envelope
      ?: JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
      ?: return emptyMap()
    return JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
  }

  /**
   * Phase producers own named result objects (`validation_result`, `history_result`,
   * `commit_push_result`, and `pr_result`). Resolve only those governed containers; searching every
   * nested object would turn a closed projection into an accidental context-discovery mechanism.
   */
  private fun resolveDeclaredPhaseField(produced: Map<String, Any?>, name: String): Any? {
    produced[name]?.let { return it }
    val resultContainers = listOf(
      "audit_result",
      "review_result",
      "validation_result",
      "history_result",
      "commit_push_result",
      "pr_result",
    )
    val nested = resultContainers.firstNotNullOfOrNull { container ->
      JsonSupport.anyToStringAnyMap(produced[container])?.get(name)
    }
    if (nested != null) return nested
    // Optional on clean validate receipts: absent means zero justifications, not a malformed receipt.
    if (name == "suppression_justifications") return emptyList<Any?>()
    return null
  }

  private fun projectionValue(
    name: String,
    value: Any,
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionValue {
    if (name == FeatureTaskRuntimeImplementationReceipt.FIELD_REPOSITORY_CHECKPOINT) {
      val checkpoint = JsonSupport.anyToStringAnyMap(value)
      val fingerprint = (checkpoint?.get("fingerprint") as? String)?.takeIf(String::isNotBlank)
        ?: reject(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "repository_checkpoint must contain a non-blank fingerprint.",
        )
      return FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        fingerprint,
      )
    }
    return when (value) {
      is Iterable<*> -> FeatureTaskRuntimeHandoffProjectionValue.TextList(
        value.map { item ->
          when (item) {
            is String -> item
            is Map<*, *> -> JsonSupport.mapToJsonString(
              item.entries.associate { (key, entryValue) -> key.toString() to entryValue },
            )
            else -> item.toString()
          }
        },
      )
      is Map<*, *> -> FeatureTaskRuntimeHandoffProjectionValue.Text(
        JsonSupport.mapToJsonString(value.entries.associate { (key, entryValue) -> key.toString() to entryValue }),
      )
      else -> FeatureTaskRuntimeHandoffProjectionValue.Text(value.toString())
    }
  }

  /**
   * Resolves the concrete bounded planning projection fields for a declared upstream edge, or null when
   * the declaration is not a planning contract (the caller falls back to the coarse whole-receipt
   * field). Parses the producing phase's schema-validated produced_outputs into the typed model and
   * renders exactly that model's declared field set, so the complete producer envelope, narration, or
   * raw payload never reaches the consumer. A plan_commitment declaration parses the source executable
   * plan and narrows it to the obligation-only subset (AC-011).
   */
  private fun planningProjectionFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    producingPhaseId: String,
    output: FeatureTaskRuntimePhaseOutput,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    val contractId = declaration.projectionContractId
    if (contractId !in PLANNING_PROJECTION_CONTRACT_IDS) return null
    // A plan_commitment is derived from the plan's executable_plan output, so the kind the PRODUCER
    // must emit is not always the kind this edge delivers.
    val expectedKind = when (contractId) {
      FeatureTaskRuntimePlanningProjectionContract.PREPLANNING_DIGEST_ID ->
        FeatureTaskRuntimeProjectionKind.PREPLANNING_DIGEST
      FeatureTaskRuntimePlanningProjectionContract.EXECUTABLE_PLAN_ID,
      FeatureTaskRuntimePlanningProjectionContract.PLAN_COMMITMENT_ID,
      -> FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN
      else -> FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT
    }
    val projection = try {
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = phaseOutputEnvelope(output, producingPhaseId),
        producingPhaseId = producingPhaseId,
        expectedKind = expectedKind,
        schemaValidator = inputs.planningProjectionValidator,
      )
    } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
      throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = error.sourceLabel,
        reason = error.reason,
        projectionName = declaration.projectionName,
        cause = error,
      )
    }
    // Exhaustive narrowing on the parsed type: no cast, so a shape the declaration did not ask for is
    // a typed rejection rather than a ClassCastException on an already-completed producing phase.
    return when {
      contractId == FeatureTaskRuntimePlanningProjectionContract.PLAN_COMMITMENT_ID &&
        projection is FeatureTaskRuntimeExecutablePlan -> projection.toPlanCommitment().toProjectionFields()
      else -> projection.toProjectionFields()
    }
  }

  private fun phaseOutputEnvelope(output: FeatureTaskRuntimePhaseOutput, producingPhaseId: String): Map<String, Any?> {
    output.normalizedOutput?.envelope?.takeIf { it.isNotEmpty() }?.let { return it }
    val parsed = output.payload.takeIf(String::isNotBlank)?.let(JsonSupport::parseObjectOrNull)
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "$producingPhaseId#produced_outputs",
        reason = "producing phase output payload is missing or not a JSON object.",
      )
    return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "$producingPhaseId#produced_outputs",
        reason = "producing phase output payload must decode to a JSON object.",
      )
  }
}
