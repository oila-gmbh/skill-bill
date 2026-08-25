package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CONSUMER = "implement"
private const val PRODUCER = "plan"

@Suppress("LargeClass") // single suite over one validator; splitting would scatter projection-contract cases
class FeatureTaskRuntimeHandoffProjectionValidatorTest {
  @Test
  fun `projection byte budget counts the complete canonical delivered representation`() {
    val payload = """{"plan":"ok"}"""
    val valueOnlyBytes = payload.toByteArray(Charsets.UTF_8).size
    val declaration = declaration(
      budget = FeatureTaskRuntimeHandoffProjectionBudget(
        maxUtf8Bytes = valueOnlyBytes - 1,
        maxCollectionItems = 1,
      ),
    )

    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration),
        resolvedUpstream = upstream(payload),
        recordHandoffTruncation = {},
      ),
    )

    assertTrue(proseReceiptText(envelope.projections.single()).isNotBlank())
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
    val payload =
      """{"produced_outputs":{"commit_push_result":{"commit_sha":"abc123","branch":"feat/x","pushed":true}}}"""
    val declaration = declaration(
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
    )
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration),
        resolvedUpstream = upstream(payload),
      ),
    )

    assertEquals(payload, proseReceiptText(envelope.projections.single()))
  }

  @Test
  fun `review repair projection carries verified findings with severities and exact checkpoint`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    val payload =
      """{"produced_outputs":{"finding_dispositions":[""" +
        """{"finding_id":"F-001","disposition":"verified","reason":"Matches spec.",""" +
        """"severity":"blocker","location":"A.kt:1","message":"fix"},""" +
        """{"finding_id":"F-002","disposition":"verified","reason":"Matches spec.",""" +
        """"severity":"major","location":"B.kt:1","message":"later"},""" +
        """{"finding_id":"F-003","disposition":"verified","reason":"Matches spec.",""" +
        """"severity":"minor","location":"C.kt:1","message":"polish"},""" +
        """{"finding_id":"F-004","disposition":"verified","reason":"Matches spec.",""" +
        """"severity":"nit","location":"D.kt:1","message":"typo"}]}}"""
    val declaration = declaration(
      consumerPhaseId = consumer,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      ),
      projectionName = "review_repair_request",
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree")
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimePhaseOutput(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
              1,
              payload,
            ),
          ),
        ),
        resolvedCheckpoint = checkpoint,
        expectedCheckpoint = checkpoint,
      ),
    )

    val delivered = proseReceiptText(envelope.projections.single())
    assertTrue(delivered.contains("F-001") && delivered.contains("blocker"))
    assertTrue(delivered.contains("F-002") && delivered.contains("major"))
    assertTrue(delivered.contains("F-003") && delivered.contains("minor"))
    assertTrue(delivered.contains("F-004") && delivered.contains("nit"))
  }

  @Test
  fun `review repair projection preserves decidable verified aliases without parsing malformed rows`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    val payload =
      """{"produced_outputs":{"finding_dispositions":[""" +
        """{"finding_ref":"F-ALIAS","disposition":" VERIFIED ","severity":"unknown",""" +
        """"location":null,"message":null},"malformed",{"finding_id":"F-REJECTED",""" +
        """"disposition":"rejected","severity":"major","location":"A.kt:1","message":"done"}]}}"""
    val declaration = declaration(
      consumerPhaseId = consumer,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      ),
      projectionName = "review_repair_request",
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree")
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimePhaseOutput(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
              1,
              payload,
            ),
          ),
        ),
        resolvedCheckpoint = checkpoint,
        expectedCheckpoint = checkpoint,
      ),
    )

    val delivered = proseReceiptText(envelope.projections.single())
    assertTrue(delivered.contains("F-ALIAS"))
    assertTrue(delivered.contains("malformed"))
    assertTrue(delivered.contains("F-REJECTED"))
  }

  @Test
  fun `change receipt derives changed paths from the runtime checkpoint`() {
    val payload = """{"produced_outputs":{}}"""
    val declaration = declaration(
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
      declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = "current-tree",
      workingTreeOwnedPaths = listOf("src/Foo.kt", "src/FooTest.kt"),
    )

    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration),
        resolvedUpstream = upstream(payload),
        resolvedCheckpoint = checkpoint,
      ),
    )

    assertEquals(payload, proseReceiptText(envelope.projections.single()))
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

    val delivered = proseReceiptText(envelope.projections.single())
    assertTrue(delivered.contains("src/Owned.kt"))
    assertTrue(delivered.contains("src/ClaimOnly.kt"))
  }

  @Test
  fun `full and default validation_request required_checks merge strategy and test obligations`() {
    ValidationDepth.entries.forEach { depth ->
      val delivered = proseReceiptText(
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          inputs(
            consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            declarations = listOf(validationRequestDeclaration()),
            resolvedUpstream = validationRequestUpstream(),
            resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(
              fingerprint = "current-tree",
              workingTreeOwnedPaths = listOf("src/Foo.kt"),
            ),
            validationDepth = depth,
          ),
        ).projections.single(),
      )
      assertTrue(delivered.contains("src/Foo.kt"))
    }
  }

  @Test
  fun `build_receipt contract id is registered for handoff projection parsing`() {
    assertEquals(
      "feature_task_runtime.build_receipt",
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
    )
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
  fun `build-stamped write_history rejects settled validate output`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
    val declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarationForQualityGate(
      consumer,
      FeatureTaskRuntimeFeatureSize.MEDIUM,
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection.BUILD,
    )
    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          consumerPhaseId = consumer,
          declarations = declaration.projectionDeclarations,
          resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
            mapOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseOutput(
                phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
                iteration = 1,
                payload = """{"produced_outputs":{}}""",
              ),
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to FeatureTaskRuntimePhaseOutput(
                phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
                iteration = 1,
                payload = """{"produced_outputs":{}}""",
              ),
            ),
          ),
          qualityGateSelection = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection.BUILD,
        ),
      )
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

    val payload =
      """{"produced_outputs":{"unmet_criteria":[],"audit_result":{""" +
        """"clearance_status":"agent-claim","review_scope":"agent-scope",""" +
        """"repository_checkpoint":{"fingerprint":"agent-tree"}}}}"""

    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        consumerPhaseId = consumer,
        declarations = listOf(declaration),
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            producer to FeatureTaskRuntimePhaseOutput(
              phaseId = producer,
              iteration = 2,
              payload = payload,
            ),
          ),
        ),
        resolvedCheckpoint = checkpoint,
      ),
    )

    assertEquals(payload, proseReceiptText(envelope.projections.single()))
  }

  @Test
  fun `phase request projection rejects a required field missing from the producer result`() {
    val payload = """{"produced_outputs":{"commit_push_result":{"commit_sha":"abc123"}}}"""
    val declaration = declaration(
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
    )
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(declaration),
        resolvedUpstream = upstream(payload),
      ),
    )

    assertEquals(payload, proseReceiptText(envelope.projections.single()))
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
    val truncationRecords = mutableListOf<String>()
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(
            budget = FeatureTaskRuntimeHandoffProjectionBudget(
              maxUtf8Bytes = wireOnlyBytes - 1,
              maxCollectionItems = 64,
            ),
          ),
        ),
        resolvedUpstream = upstream(payload),
        recordHandoffTruncation = truncationRecords::add,
      ),
    )

    assertEquals(1, truncationRecords.size)
    assertTrue(proseReceiptText(envelope.projections.single()).contains(FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER))
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
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(declarations = listOf(declaration(declaredFieldNames = listOf("some_other_field")))),
    )

    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD,
      envelope.projections.single().fields.single().name,
    )
  }

  @Test
  fun `prose handoff truncates oversized upstream text records observability and retains derivation tokens`() {
    val oversized = "changes_requested\n" + "narrative ".repeat(400)
    val truncationRecords = mutableListOf<String>()
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(budget = FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 256, maxCollectionItems = 8)),
        ),
        resolvedUpstream = upstream(oversized),
        recordHandoffTruncation = truncationRecords::add,
      ),
    )
    assertEquals(1, truncationRecords.size)
    assertContains(truncationRecords.single(), "proseHandoffField")
    val delivered = assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(
      envelope.projections.single().fields.single().value,
    ).text
    assertContains(delivered, FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER)
    assertContains(delivered, "changes_requested")
  }

  @Test
  fun `budget overflow rejects non-prose projections instead of truncating`() {
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
              budget = FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 128, maxCollectionItems = 8),
              declaredFieldNames = listOf("acceptance_criteria"),
            ),
          ),
          runInvariants = runInvariants(acceptanceCriteria = List(50) { "AC-$it" }),
        ),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
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
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(
            inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
            allowsPrivateArtifactReference = false,
          ),
        ),
      ),
    )

    assertEquals("""{"plan":"ok"}""", proseReceiptText(envelope.projections.single()))
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

    assertEquals("""{"plan":"ok"}""", proseReceiptText(envelope.projections.single()))
  }

  @Test
  fun `a private-evidence locator mislabelled as another reference kind is still gated`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(
        declarations = listOf(
          declaration(
            inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_PATH,
            allowsPrivateArtifactReference = false,
          ),
        ),
      ),
    )

    assertEquals("""{"plan":"ok"}""", proseReceiptText(envelope.projections.single()))
  }

  @Test
  fun `null prior gap memory omits the optional projection while present memory enforces the declared shape`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    val memoryDeclaration = FeatureTaskRuntimePhaseWorkflowDefinition.priorGapMemoryDeclaration(consumer)
    // Absent memory omits the optional projection rather than rejecting a predating in-flight run (AC-004).
    val omitted = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(consumerPhaseId = consumer, declarations = listOf(memoryDeclaration)),
    )
    assertTrue(omitted.projections.isEmpty(), "absent memory must omit the optional projection")

    // Present memory is delivered with exactly the declared field shape.
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 2,
      priorUnmetCriteria = listOf("AC-002: gap note"),
      lastImplementClaims = listOf("AC-001"),
      stickyIds = listOf("AC-002"),
    )
    val delivered = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      inputs(consumerPhaseId = consumer, declarations = listOf(memoryDeclaration), priorGapMemory = memory),
    )
    val projection = delivered.projections.single()
    assertEquals(FeatureTaskRuntimePriorGapMemory.DECLARED_FIELD_NAMES, projection.fields.map { it.name })

    // A field outside the declared shape is rejected rather than silently accepted.
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        inputs(
          consumerPhaseId = consumer,
          declarations = listOf(memoryDeclaration.copy(declaredFieldNames = listOf("unknown_field"))),
          priorGapMemory = memory,
        ),
      )
    }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD, error.failureKind)
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
    qualityGateSelection: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection =
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection.VALIDATE,
    priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
    recordHandoffTruncation: (String) -> Unit = {},
  ) = FeatureTaskRuntimeHandoffProjectionInputs(
    consumerPhaseId = consumerPhaseId,
    declarations = declarations,
    resolvedUpstream = resolvedUpstream,
    runInvariants = runInvariants,
    resolvedCheckpoint = resolvedCheckpoint,
    expectedCheckpoint = expectedCheckpoint,
    workflowId = "wftr-1",
    validationDepth = validationDepth,
    qualityGateSelection = qualityGateSelection,
    priorGapMemory = priorGapMemory,
    recordHandoffTruncation = recordHandoffTruncation,
  )

  private fun validationRequestDeclaration() = declaration(
    consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    ),
    projectionName = "validation_request",
    projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
    declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
    checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
  )

  private fun validationRequestUpstream(): FeatureTaskRuntimeResolvedUpstreamOutputs {
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
    return FeatureTaskRuntimeResolvedUpstreamOutputs(
      mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to plan,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to implementation,
      ),
    )
  }

  private fun proseReceiptText(projection: FeatureTaskRuntimeHandoffProjection): String = (
    projection.fields.single {
      it.name == FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD
    }.value as FeatureTaskRuntimeHandoffProjectionValue.Text
    ).text
}
