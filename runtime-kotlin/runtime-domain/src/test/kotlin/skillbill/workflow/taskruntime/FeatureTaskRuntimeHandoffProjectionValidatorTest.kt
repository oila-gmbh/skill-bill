package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
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

@Suppress("LargeClass") // single suite over one validator; splitting would scatter projection-contract cases
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
              """{"produced_outputs":{"findings":[""" +
                """{"finding_id":"F-001","severity":"Blocker","location":"A.kt:1","message":"fix"},""" +
                """{"finding_id":"F-002","severity":"Major","location":"B.kt:1","message":"later"}]}}""",
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
  fun `change receipt derives changed paths from the runtime checkpoint`() {
    val declaration = declaration(
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
      declaredFieldNames = listOf(
        "changed_paths",
        "tests_added",
        "tests_updated",
        "deviations",
        "repository_checkpoint",
      ),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = "current-tree",
      workingTreeOwnedPaths = listOf("src/Foo.kt", "src/FooTest.kt"),
    )

    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration),
        resolvedUpstream = upstream("""{"produced_outputs":{}}"""),
        resolvedCheckpoint = checkpoint,
      ),
    )

    val fields = envelope.projections.single().fields.associateBy { it.name }
    val changedPaths = assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
      fields.getValue("changed_paths").value,
    )
    assertEquals(listOf("src/Foo.kt", "src/FooTest.kt"), changedPaths.items)
    assertEquals(
      emptyList(),
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
        fields.getValue("tests_added").value,
      ).items,
    )
  }

  @Test
  fun `finalization inventory reconciles receipt claims to runtime owned paths`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
    val declaration = declaration(
      consumerPhaseId = consumer,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
      declaredFieldNames = listOf(
        "path_inventory",
        "required_inclusions",
        "required_exclusions",
        "branch_identity",
        "gate_attestations",
        "repository_checkpoint",
      ),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = "current-tree",
      baseRef = "base",
      headRef = "head",
      workingTreeOwnedPaths = listOf("src/Owned.kt", "src/OwnedTest.kt"),
    )
    val implementation = FeatureTaskRuntimePhaseOutput(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      iteration = 1,
      payload = """{"produced_outputs":{"changed_paths":["src/Owned.kt","src/ClaimOnly.kt"]}}""",
    )

    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to implementation),
        ),
        resolvedCheckpoint = checkpoint,
      ),
    )

    val fields = envelope.projections.single().fields.associateBy { it.name }
    assertEquals(
      listOf("src/Owned.kt", "src/OwnedTest.kt"),
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(fields.getValue("path_inventory").value).items,
    )
    assertEquals(
      listOf("src/Owned.kt", "src/OwnedTest.kt"),
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(fields.getValue("required_inclusions").value).items,
    )
    assertEquals(
      listOf("src/ClaimOnly.kt"),
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(fields.getValue("required_exclusions").value).items,
    )
  }

  @Test
  fun `build_only validation_request required_checks is compile buildability only`() {
    val fields = validationRequestFields(ValidationDepth.BUILD_ONLY)
    val requiredChecks = assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
      fields.getValue("required_checks").value,
    ).items
    assertEquals(
      listOf(FeatureTaskRuntimeHandoffProjectionValidator.BUILD_ONLY_COMPILE_BUILDABILITY_CHECK),
      requiredChecks,
    )
    assertFalse(requiredChecks.any { it.contains("test", ignoreCase = true) })
    assertFalse(requiredChecks.contains("Focused runtime tests."))
    assertFalse(requiredChecks.contains("Focused unit test."))
    // Shape stays stable: strategy is still projected, only required_checks contents narrow.
    assertEquals(
      listOf("Focused runtime tests."),
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
        fields.getValue("validation_strategy").value,
      ).items,
    )
  }

  @Test
  fun `full and default validation_request required_checks merge strategy and test obligations`() {
    listOf(ValidationDepth.FULL, ValidationDepth.DEFAULT).forEach { depth ->
      val requiredChecks = assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
        validationRequestFields(depth).getValue("required_checks").value,
      ).items
      assertEquals(listOf("Focused runtime tests.", "Focused unit test."), requiredChecks)
    }
  }

  @Test
  fun `validation_receipt declared fields stay validation_status checks repository_checkpoint`() {
    val expected = listOf(
      "validation_status",
      "checks",
      "repository_checkpoint",
      "gate_run_count",
      "gate_runs",
    )
    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    ).forEach { consumer ->
      val receipt = FeatureTaskRuntimePhaseWorkflowDefinition
        .phaseDeclaration(consumer, FeatureTaskRuntimeFeatureSize.MEDIUM)
        .projectionDeclarations
        .single {
          it.projectionContractId ==
            FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT
        }
      assertEquals(expected, receipt.declaredFieldNames)
    }
  }

  @Test
  fun `audit clearance derives gate status scope and checkpoint from runtime-owned facts`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
    val declaration = declaration(
      consumerPhaseId = consumer,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producer),
      projectionName = "audit_clearance",
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      declaredFieldNames = listOf("clearance_status", "review_scope", "repository_checkpoint"),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("runtime-tree")

    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            producer to FeatureTaskRuntimePhaseOutput(
              phaseId = producer,
              iteration = 2,
              payload = """{"produced_outputs":{"unmet_criteria":[],"audit_result":{""" +
                """"clearance_status":"agent-claim","review_scope":"agent-scope",""" +
                """"repository_checkpoint":{"fingerprint":"agent-tree"}}}}""",
            ),
          ),
        ),
        resolvedCheckpoint = checkpoint,
      ),
    )

    val fields = envelope.projections.single().fields.associateBy { it.name }
    assertEquals(
      FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(fields.getValue("clearance_status").value).text,
    )
    assertEquals(
      "branch_diff",
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(fields.getValue("review_scope").value).text,
    )
    assertEquals(
      "runtime-tree",
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.CompactReference>(
        fields.getValue("repository_checkpoint").value,
      ).value,
    )
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
  fun `must_match refreshes instead of rejecting repository movement`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH),
        ),
        resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
        expectedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-def"),
      ),
    )
    assertEquals("head-abc", envelope.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `must_match does not require a recorded checkpoint`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH),
        ),
        resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc"),
      ),
    )
    assertEquals("head-abc", envelope.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `must_match accepts identical runtime checkpoints`() {
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc")
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration(checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH)),
        resolvedCheckpoint = checkpoint,
        expectedCheckpoint = checkpoint,
      ),
    )
    assertEquals("head-abc", envelope.repositoryCheckpoint?.fingerprint)
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

  @Suppress("LongParameterList")
  private fun inputs(
    consumerPhaseId: String = CONSUMER,
    declarations: List<PhaseHandoffProjectionDeclaration> = listOf(declaration()),
    resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs = upstream(),
    runInvariants: FeatureTaskRuntimeRunInvariants = runInvariants(),
    resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
    expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
    validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  ) = FeatureTaskRuntimeHandoffProjectionInputs(
    consumerPhaseId = consumerPhaseId,
    declarations = declarations,
    resolvedUpstream = resolvedUpstream,
    runInvariants = runInvariants,
    resolvedCheckpoint = resolvedCheckpoint,
    expectedCheckpoint = expectedCheckpoint,
    workflowId = "wftr-1",
    validationDepth = validationDepth,
  )

  private fun validationRequestFields(
    validationDepth: ValidationDepth,
  ): Map<String, FeatureTaskRuntimeHandoffProjectionField> {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    val declaration = declaration(
      consumerPhaseId = consumer,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      projectionName = "validation_request",
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
      declaredFieldNames = listOf(
        "validation_strategy",
        "changed_paths",
        "required_checks",
        "repository_checkpoint",
      ),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    )
    val plan = FeatureTaskRuntimePhaseOutput(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      iteration = 1,
      payload = """{"produced_outputs":{"projection_kind":"executable_plan","contract_version":"0.1",""" +
        """"mode":"direct","tasks":[{"task_id":"task-1","description":"Fixture.",""" +
        """"criterion_refs":["AC-001"],"target_paths_or_symbols":["src/Foo.kt"],""" +
        """"test_obligations":["Focused unit test."]}],""" +
        """"validation_strategy":["Focused runtime tests."]}}""",
    )
    val implementation = FeatureTaskRuntimePhaseOutput(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      iteration = 1,
      payload = """{"produced_outputs":{"changed_paths":["src/Foo.kt"]}}""",
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = "validate-tree",
      workingTreeOwnedPaths = listOf("src/Foo.kt"),
    )
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to plan,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to implementation,
          ),
        ),
        resolvedCheckpoint = checkpoint,
        validationDepth = validationDepth,
      ),
    )
    return envelope.projections.single().fields.associateBy { it.name }
  }
}
