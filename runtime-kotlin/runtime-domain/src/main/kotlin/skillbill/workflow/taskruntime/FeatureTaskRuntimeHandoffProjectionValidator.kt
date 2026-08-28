package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

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
    rejectConflictingGateReceipts(inputs)
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

  private fun rejectConflictingGateReceipts(inputs: FeatureTaskRuntimeHandoffProjectionInputs) {
    val consumer = inputs.consumerPhaseId
    if (
      consumer != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY &&
      consumer != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
    ) {
      return
    }
    val buildCompleted = inputs.resolvedUpstream.outputsByPhaseId.containsKey(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
    )
    val validateCompleted = inputs.resolvedUpstream.outputsByPhaseId.containsKey(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
    )
    when (inputs.qualityGateSelection) {
      FeatureTaskRuntimeQualityGateSelection.BUILD ->
        if (validateCompleted) {
          reject(
            inputs,
            inputs.declarations.first(),
            FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
            "build-stamped child cannot carry a settled validation_receipt from validate.",
          )
        }
      FeatureTaskRuntimeQualityGateSelection.VALIDATE ->
        if (buildCompleted) {
          reject(
            inputs,
            inputs.declarations.first(),
            FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
            "validate-stamped child cannot carry a settled build_receipt from build.",
          )
        }
    }
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
    val refreshed = fields.map { field ->
      resolvedCheckpointField(field, resolvedFingerprint, carried)
    }
    if (
      REPOSITORY_CHECKPOINT_FIELD in declaration.declaredFieldNames &&
      refreshed.none { it.name == REPOSITORY_CHECKPOINT_FIELD }
    ) {
      return refreshed + FeatureTaskRuntimeHandoffProjectionField(
        REPOSITORY_CHECKPOINT_FIELD,
        FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
          kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
          value = resolvedFingerprint,
        ),
      )
    }
    return refreshed
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
  ): FeatureTaskRuntimeHandoffProjectionField = if (field.name == REPOSITORY_CHECKPOINT_FIELD) {
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
    fields.firstOrNull { it.name == REPOSITORY_CHECKPOINT_FIELD }
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

  @Suppress("CyclomaticComplexMethod")
  private fun fieldsFor(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? = when (val sourceRef = declaration.sourceRef) {
    is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput ->
      inputs.resolvedUpstream.outputsByPhaseId[sourceRef.producingPhaseId]?.let { output ->
        phaseProjectionFields(inputs, declaration, output)
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
      }
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
    FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory -> inputs.priorGapMemory?.toProjectionFields()
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
      if (declaredName !in seen && declaration.required && !optionalDeclaredField(declaration, declaredName)) {
        reject(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "declared field '$declaredName' resolved to no value.",
        )
      }
    }
  }

  private fun optionalDeclaredField(declaration: PhaseHandoffProjectionDeclaration, fieldName: String): Boolean =
    declaration.projectionContractId ==
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE &&
      fieldName == "directive"

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
  private const val REPOSITORY_CHECKPOINT_FIELD: String = "repository_checkpoint"

  private val SUPPORTED_PROJECTION_CONTRACT_VERSIONS: Set<String> = setOf("0.1", "0.2", "0.3")

  private val PHASE_PROJECTION_CONTRACT_IDS: Set<String> = setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_CLEARANCE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
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
    val runtimeOwned = runtimeOwnedPhaseProjectionValues(inputs, declaration, produced, envelope)
    return declaration.declaredFieldNames.mapNotNull { name ->
      val value = runtimeOwned[name] ?: when {
        declaration.projectionContractId ==
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE -> null
        name == "verdict" -> envelope[name]
        else -> resolveDeclaredPhaseField(produced, name)
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
    envelope: Map<String, Any?>,
  ): Map<String, Any?> = when (declaration.projectionContractId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE -> mapOf(
      "clearance_status" to auditClearanceStatus(envelope),
      "review_scope" to FeatureTaskRuntimePhaseWorkflowDefinition
        .ceremonyScaling(inputs.runInvariants.featureSize)
        .reviewScope
        .wireValue,
      "repository_checkpoint" to checkpointFingerprint(inputs),
      "verdict" to auditClearanceStatus(envelope),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST -> mapOf(
      "unresolved_blocker_findings" to verifiedFindingsProjection(produced),
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT -> mapOf(
      "findings" to reviewFindingsForVerificationProjection(produced),
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS -> mapOf(
      "finding_dispositions" to produced["finding_dispositions"],
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT -> mapOf(
      "changed_paths" to inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty(),
      "tests_added" to (produced["tests_added"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "tests_updated" to (produced["tests_updated"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "deviations" to (produced["deviations"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE ->
      phaseProseProjectionValues(inputs, declaration, produced)
    else -> finalizationProjectionValues(inputs, declaration)
  }.filterValues { it != null }

  private fun checkpointFingerprint(inputs: FeatureTaskRuntimeHandoffProjectionInputs): Map<String, String>? =
    inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) }

  private fun phaseProseProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    produced: Map<String, Any?>,
  ): Map<String, Any?> {
    val value = resolveDeclaredPhaseField(produced, "value")
      ?: reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "produced_outputs.value is required for phase prose handoff.",
      )
    val valueText = value.toString()
    if (valueText.isBlank()) {
      reject(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "produced_outputs.value must contain non-blank prose for phase handoff.",
      )
    }
    val fields = linkedMapOf<String, Any?>("value" to valueText)
    resolveDeclaredPhaseField(produced, "prompt")
      ?.toString()
      ?.takeIf(String::isNotBlank)
      ?.let { fields["directive"] = it }
    return fields
  }

  private fun auditClearanceStatus(envelope: Map<String, Any?>): String? =
    (envelope["verdict"] as? String)?.takeIf(String::isNotBlank)

  private fun verifiedFindingsProjection(produced: Map<String, Any?>): List<Map<String, Any?>> =
    FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      produced["finding_dispositions"],
      "produced_outputs.finding_dispositions",
    )
      .filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED }
      .map { disposition ->
        mapOf(
          "finding_id" to disposition.findingId,
          "severity" to disposition.severity.wireValue,
          "location" to disposition.location,
          "expected_outcome" to disposition.message,
          "criterion_refs" to emptyList<String>(),
          "task_refs" to emptyList<String>(),
        )
      }

  private fun reviewFindingsForVerificationProjection(produced: Map<String, Any?>): List<Map<String, Any?>> =
    (produced["findings"] as? List<*>).orEmpty()
      .mapNotNull(JsonSupport::anyToStringAnyMap)
      .map { finding ->
        val severity = (finding["severity"] as? String)?.takeIf(String::isNotBlank) ?: "blocker"
        mapOf(
          "finding_id" to (finding["finding_id"] ?: finding["f_number"] ?: finding["id"]),
          "severity" to severity,
          "location" to (
            finding["location"] ?: finding["repository_path"] ?: finding["path"] ?: "repository"
            ),
          "message" to (
            finding["message"] ?: finding["description"] ?: finding["expected_outcome"] ?: "Review finding."
            ),
          "issue_category" to (finding["issue_category"] ?: finding["category"] ?: "other"),
          "claim_verdict" to finding["claim_verdict"],
          "scope_disposition" to finding["scope_disposition"],
        ).filterValues { it != null }
      }

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
      "build_receipt",
      "history_result",
      "commit_push_result",
      "pr_result",
    )
    val nested = resultContainers.firstNotNullOfOrNull { container ->
      JsonSupport.anyToStringAnyMap(produced[container])?.get(name)
    }
    if (nested != null) return nested
    return null
  }

  private fun projectionValue(
    name: String,
    value: Any,
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionValue {
    if (name == REPOSITORY_CHECKPOINT_FIELD) {
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
}
