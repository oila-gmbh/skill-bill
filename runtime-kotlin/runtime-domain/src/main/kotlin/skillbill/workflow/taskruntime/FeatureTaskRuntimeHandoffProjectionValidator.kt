package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope

/**
 * Builds the delivered handoff envelope from static declarations, rejecting rather than repairing.
 *
 * Budgets are counted in UTF-8 bytes and collection items *before* the envelope exists, so an
 * oversized projection can never be truncated, silently dropped, or swapped for its full source
 * artifact — it fails the launch with a typed error naming the projection and its contract.
 */
@Suppress("TooManyFunctions") // one cohesive validation seam; each function is a named rejection rule
object FeatureTaskRuntimeHandoffProjectionValidator {
  const val COMPACT_REFERENCE_MAX_LENGTH: Int = 512

  @Suppress("ThrowsCount") // one rejection per AC-007 failure mode; collapsing them would blur diagnoses
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
   * `must_match` therefore compares only the two runtime-produced fingerprints — the freshly resolved
   * one and the one recorded earlier for this run — which is the comparison that actually detects a
   * moved tree. `refresh_from_repository` keeps the producer's claim fields untouched and rewrites the
   * repository-derived checkpoint field to the resolved fingerprint, appending the producer's claim so
   * the substitution is visible rather than silent (AC-012).
   */
  private fun enforceCheckpointPolicy(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ): List<FeatureTaskRuntimeHandoffProjectionField> {
    val carried = receiptCarriedCheckpointFingerprint(fields)
    val violation = when (declaration.checkpointPolicy) {
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED -> null
      FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY ->
        if (inputs.resolvedCheckpoint == null) {
          "policy refresh_from_repository requires a freshly resolved repository checkpoint, none was supplied."
        } else {
          null
        }
      FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH -> mustMatchViolation(inputs)
    }
    if (violation != null) {
      reject(inputs, declaration, FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION, violation)
    }
    if (declaration.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY) {
      return fields
    }
    val resolvedFingerprint = inputs.resolvedCheckpoint?.fingerprint ?: return fields
    return fields.map { field ->
      if (field.name == FeatureTaskRuntimeImplementationReceipt.FIELD_REPOSITORY_CHECKPOINT) {
        field.copy(
          value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
            value = resolvedFingerprint + (carried?.let { CHECKPOINT_PRODUCER_CLAIM_SEPARATOR + it }.orEmpty()),
          ),
        )
      } else {
        field
      }
    }
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

  private fun mustMatchViolation(inputs: FeatureTaskRuntimeHandoffProjectionInputs): String? {
    val resolved = inputs.resolvedCheckpoint
      ?: return "policy must_match requires a resolved repository checkpoint, none was supplied."
    val expected = inputs.expectedCheckpoint
      ?: return "policy must_match requires a recorded repository checkpoint to compare against, none was supplied."
    return if (resolved.fingerprint == expected.fingerprint) {
      null
    } else {
      "policy must_match requires the resolved repository fingerprint to equal the recorded one; they differ."
    }
  }

  // Returns null when a non-required source has no recorded value, so an optional projection is
  // omitted rather than delivered empty. A required source with no value is a hard rejection.
  private fun resolveFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    val fields = when (val sourceRef = declaration.sourceRef) {
      is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput ->
        inputs.resolvedUpstream.outputsByPhaseId[sourceRef.producingPhaseId]?.let { output ->
          planningProjectionFields(inputs, declaration, sourceRef.producingPhaseId, output)
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
   * not comparable values — see `enforceCheckpointPolicy` — so the claim is carried as provenance, not
   * as a superseded fingerprint. Single-token, so the field stays a compact reference.
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
    val projection = featureTaskRuntimePlanningProjectionFromEnvelope(
      envelope = phaseOutputEnvelope(output, producingPhaseId),
      producingPhaseId = producingPhaseId,
      expectedKind = expectedKind,
      schemaValidator = inputs.planningProjectionValidator,
    )
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
