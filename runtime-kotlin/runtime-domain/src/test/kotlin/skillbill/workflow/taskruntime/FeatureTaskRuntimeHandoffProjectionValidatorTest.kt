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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
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
  fun `projection byte budget counts the complete canonical delivered representation`() {
    val valueOnlyBytes = """{"plan":"ok"}""".toByteArray(Charsets.UTF_8).size
    val declaration = declaration(
      budget = FeatureTaskRuntimeHandoffProjectionBudget(
        maxUtf8Bytes = valueOnlyBytes,
        maxCollectionItems = 1,
      ),
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(inputs(declarations = listOf(declaration)))
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
  }

  @Test
  fun `projection byte size equals its canonical delivered rendering`() {
    val projection = FeatureTaskRuntimeHandoffProjectionValidator.validate(inputs())
      .projections.single()

    assertEquals(
      projection.canonicalDeliveredRendering.toByteArray(Charsets.UTF_8).size,
      projection.utf8ByteSize,
    )
  }

  @Test
  fun `projection identity uses the resolved producer attempt`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            PRODUCER to FeatureTaskRuntimePhaseOutput(
              phaseId = PRODUCER,
              iteration = 7,
              payload = """{"plan":"ok"}""",
            ),
          ),
        ),
      ),
    )

    assertEquals(FeatureTaskRuntimeProducerIteration(PRODUCER, 7), envelope.projections.single().producerIteration)
  }

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
  fun `phase request projection resolves fields from its governed result container`() {
    val declaration = declaration(
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      declaredFieldNames = listOf("commit_sha", "branch", "pushed"),
    )
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration),
        resolvedUpstream = upstream(
          """{"produced_outputs":{"commit_push_result":{"commit_sha":"abc123","branch":"feat/x","pushed":true}}}""",
        ),
      ),
    )

    assertEquals(listOf("commit_sha", "branch", "pushed"), envelope.projections.single().fields.map { it.name })
  }

  @Test
  fun `review repair projection keeps only unresolved Blocker findings and exact reviewed checkpoint`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    val declaration = declaration(
      consumerPhaseId = consumer,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      ),
      projectionName = "review_repair_request",
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      declaredFieldNames = listOf("unresolved_blocker_findings", "repository_checkpoint"),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree")
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to FeatureTaskRuntimePhaseOutput(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
              1,
              """{"produced_outputs":{"findings":[{"finding_id":"F-001","severity":"Blocker","location":"A.kt:1","message":"fix"},{"finding_id":"F-002","severity":"Major","location":"B.kt:1","message":"later"}]}}""",
            ),
          ),
        ),
        resolvedCheckpoint = checkpoint,
        expectedCheckpoint = checkpoint,
      ),
    )

    val fields = envelope.projections.single().fields
    assertEquals(listOf("unresolved_blocker_findings", "repository_checkpoint"), fields.map { it.name })
    val blockers = assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(fields.first().value)
    assertEquals(1, blockers.items.size)
    assertContains(blockers.items.single(), "F-001")
    assertFalse(blockers.items.single().contains("F-002"))
  }

  @Test
  fun `phase request projection rejects a required field missing from the producer result`() {
    val declaration = declaration(
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      declaredFieldNames = listOf("commit_sha", "branch"),
    )
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(declaration),
          resolvedUpstream = upstream(
            """{"produced_outputs":{"commit_push_result":{"commit_sha":"abc123"}}}""",
          ),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD, error.failureKind)
    assertContains(error.message.orEmpty(), "declared field 'branch' resolved to no value")
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
  fun `budget enforcement measures the exact prompt-visible rendering`() {
    val payload = """{"items":["${"x".repeat(80)}"]}"""
    val wireOnlyBytes = payload.toByteArray(Charsets.UTF_8).size
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          declarations = listOf(
            declaration(
              budget = FeatureTaskRuntimeHandoffProjectionBudget(
                maxUtf8Bytes = wireOnlyBytes + 1,
                maxCollectionItems = 64,
              ),
            ),
          ),
          resolvedUpstream = upstream(payload),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
    assertContains(error.message.orEmpty(), "UTF-8 bytes")
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
    projectionContractId: String = "test.upstream_phase_receipt",
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
    projectionContractId = projectionContractId,
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
    consumerPhaseId: String = CONSUMER,
    declarations: List<PhaseHandoffProjectionDeclaration> = listOf(declaration()),
    resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs = upstream(),
    runInvariants: FeatureTaskRuntimeRunInvariants = runInvariants(),
    resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
    expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  ) = FeatureTaskRuntimeHandoffProjectionInputs(
    consumerPhaseId = consumerPhaseId,
    declarations = declarations,
    resolvedUpstream = resolvedUpstream,
    runInvariants = runInvariants,
    resolvedCheckpoint = resolvedCheckpoint,
    expectedCheckpoint = expectedCheckpoint,
    workflowId = "wftr-1",
  )
}
