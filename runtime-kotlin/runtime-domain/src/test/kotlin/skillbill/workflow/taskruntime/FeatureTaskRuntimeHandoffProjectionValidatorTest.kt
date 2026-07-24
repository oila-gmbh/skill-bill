package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CONSUMER = "implement"
private const val PRODUCER = "plan"

class FeatureTaskRuntimeHandoffProjectionValidatorTest {
  @Test
  fun `a declared upstream receipt is projected within budget`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(inputs())

    assertEquals(1, envelope.projections.size)
    val projection = envelope.projections.single()
    assertEquals("plan_receipt", projection.projectionName)
    assertEquals(
      """{"plan":"ok"}""",
      (projection.fields.single().value as FeatureTaskRuntimeHandoffProjectionValue.Text).text,
    )
  }

  @Test
  fun `a missing required source is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap())),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.MISSING_REQUIRED_SOURCE, error.failureKind)
    assertEquals(CONSUMER, error.consumerPhaseId)
    assertEquals("wftr-1", error.workflowId)
  }

  @Test
  fun `a non-required missing source is omitted rather than rejected`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration(required = false)),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
      ),
    )

    assertTrue(envelope.projections.isEmpty())
  }

  @Test
  fun `a duplicate projection name is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(declarations = listOf(declaration(), declaration())),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.DUPLICATE_PROJECTION_NAME, error.failureKind)
  }

  @Test
  fun `a declaration for another consumer phase is rejected as malformed`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(declarations = listOf(declaration(consumerPhaseId = "audit"))),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD, error.failureKind)
  }

  @Test
  fun `an unsupported projection contract version is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(declarations = listOf(declaration(contractVersion = "9.9"))),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.UNSUPPORTED_CONTRACT_VERSION, error.failureKind)
    assertContains(error.message.orEmpty(), "9.9")
  }

  @Test
  fun `a field outside the declared shape is rejected as undeclared`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(declarations = listOf(declaration(declaredFieldNames = listOf("some_other_field")))),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD, error.failureKind)
  }

  @Test
  fun `budget overflow rejects instead of truncating or substituting the source artifact`() {
    val oversized = """{"plan":"${"p".repeat(5_000)}"}"""
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(budget = FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 128, maxCollectionItems = 8)),
          ),
          resolvedUpstream = upstream(oversized),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
    assertFalse(
      error.message.orEmpty().contains("ppppppppppppppppppppppppppppppp"),
      "the rejection echoed the oversized body; a typed error must name identifiers, not payload content",
    )
    assertContains(error.message.orEmpty(), "128-byte budget")
  }

  @Test
  fun `collection item overflow rejects instead of dropping items`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            PhaseHandoffProjectionDeclaration(
              consumerPhaseId = CONSUMER,
              sourceRef = FeatureTaskRuntimeHandoffSourceRef
                .RunInvariantField(FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA),
              projectionName = "criteria",
              projectionContractId = "test.criteria",
              projectionContractVersion = "0.1",
              promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
              budget = FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 100_000, maxCollectionItems = 2),
              declaredFieldNames = listOf("acceptance_criteria"),
            ),
          ),
          runInvariants = runInvariants(acceptanceCriteria = listOf("AC-1", "AC-2", "AC-3")),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
    assertContains(error.message.orEmpty(), "3 items")
  }

  @Test
  fun `must_match rejects a stale checkpoint and accepts a matching one`() {
    val stale = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH),
          ),
          resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
          expectedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-def"),
        ),
      )
    }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION, stale.failureKind)

    val matched = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH)),
        resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
        expectedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
      ),
    )
    assertEquals("head-abc", matched.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `must_match without a recorded checkpoint is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH),
          ),
          resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION, error.failureKind)
  }

  @Test
  fun `refresh_from_repository requires a freshly resolved checkpoint`() {
    val missing = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY),
          ),
        ),
      )
    }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION, missing.failureKind)

    val refreshed = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY),
        ),
        resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(
          fingerprint = "head-abc",
          baseRef = "main",
          headRef = "feat/x",
          workingTreeOwnedPaths = listOf("src/Main.kt"),
        ),
      ),
    )
    assertEquals(listOf("src/Main.kt"), refreshed.repositoryCheckpoint?.workingTreeOwnedPaths)
  }

  @Test
  fun `an unauthorized private-evidence reference is rejected as an invalid compact reference`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(
              inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
              allowsPrivateArtifactReference = false,
            ),
          ),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE, error.failureKind)
  }

  @Test
  fun `an authorized private-evidence reference replaces inline content with a deterministic locator`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(
            inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
            allowsPrivateArtifactReference = true,
          ),
        ),
      ),
    )

    val value = envelope.projections.single().fields.single().value
    val reference = assertIs<FeatureTaskRuntimeHandoffProjectionValue.CompactReference>(value)
    assertEquals(FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT, reference.kind)
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValidator.privateEvidenceReference(PRODUCER, 1),
      reference.value,
    )
    assertTrue(reference.kind.runtimeResolvable, "a private-artifact reference must be runtime-resolvable")
    assertFalse(reference.value.contains("""{"plan":"""), "the reference must not inline the private body")
  }

  @Test
  fun `a private-evidence locator mislabelled as another reference kind is still gated`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(
              inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_PATH,
              allowsPrivateArtifactReference = false,
            ),
          ),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE, error.failureKind)
    assertContains(error.message.orEmpty(), "private evidence artifact")
  }

  @Suppress("LongParameterList") // mirrors the declaration record under test; each field is varied by a case
  private fun declaration(
    consumerPhaseId: String = CONSUMER,
    sourceRef: FeatureTaskRuntimeHandoffSourceRef =
      FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(PRODUCER),
    projectionName: String = "plan_receipt",
    contractVersion: String = "0.1",
    promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility =
      FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget: FeatureTaskRuntimeHandoffProjectionBudget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
    declaredFieldNames: List<String> =
      listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
    required: Boolean = true,
    allowsPrivateArtifactReference: Boolean = false,
    inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? = null,
  ) = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = sourceRef,
    projectionName = projectionName,
    projectionContractId = "test.upstream_phase_receipt",
    projectionContractVersion = contractVersion,
    promptVisibility = promptVisibility,
    budget = budget,
    declaredFieldNames = declaredFieldNames,
    checkpointPolicy = checkpointPolicy,
    required = required,
    allowsPrivateArtifactReference = allowsPrivateArtifactReference,
    inlineAlternative = inlineAlternative,
  )

  private fun upstream(payload: String = """{"plan":"ok"}""") = FeatureTaskRuntimeResolvedUpstreamOutputs(
    mapOf(PRODUCER to FeatureTaskRuntimePhaseOutput(phaseId = PRODUCER, iteration = 1, payload = payload)),
  )

  private fun runInvariants(acceptanceCriteria: List<String> = listOf("AC-1")) = FeatureTaskRuntimeRunInvariants(
    specReference = ".feature-specs/SKILL-137/spec.md",
    acceptanceCriteria = acceptanceCriteria,
    mandatesAndOverrides = emptyList(),
  )

  private fun inputs(
    declarations: List<PhaseHandoffProjectionDeclaration> = listOf(declaration()),
    resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs = upstream(),
    runInvariants: FeatureTaskRuntimeRunInvariants = runInvariants(),
    resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
    expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  ) = FeatureTaskRuntimeHandoffProjectionInputs(
    consumerPhaseId = CONSUMER,
    declarations = declarations,
    resolvedUpstream = resolvedUpstream,
    runInvariants = runInvariants,
    resolvedCheckpoint = resolvedCheckpoint,
    expectedCheckpoint = expectedCheckpoint,
    workflowId = "wftr-1",
  )
}
