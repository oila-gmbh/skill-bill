package skillbill.workflow.taskruntime

import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection.BUILD
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection.VALIDATE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDelivery
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionShape
import kotlin.test.assertTrue

internal const val HANDOFF_VALIDATOR_TEST_CONSUMER: String = "implement"
internal const val HANDOFF_VALIDATOR_TEST_PRODUCER: String = "plan"
internal const val HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD: String =
  """{"produced_outputs":{"validation_result":{"validation_status":"passed","checks":[],""" +
    """"repository_checkpoint":{"fingerprint":"tree-1"},"gate_run_count":1,"gate_runs":[]}}}"""
internal const val HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD: String =
  """{"produced_outputs":{"history_result":{"changed_paths":["src/Foo.kt"],"decisions_recorded":[]}}}"""
internal const val HANDOFF_VALIDATOR_COMMIT_PUSH_PHASE_PAYLOAD: String =
  """{"produced_outputs":{"commit_push_result":{"commit_sha":"abc","branch":"feat",""" +
    """"base_branch":"main","pushed":true}}}"""

internal data class HandoffProjectionDeclarationFixture(
  var consumerPhaseId: String = HANDOFF_VALIDATOR_TEST_CONSUMER,
  var sourceRef: FeatureTaskRuntimeHandoffSourceRef =
    FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(HANDOFF_VALIDATOR_TEST_PRODUCER),
  var projectionName: String = "plan_receipt",
  var projectionContractId: String = "test.upstream_phase_receipt",
  var contractVersion: String = "0.1",
  var promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility =
    FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
  var budget: FeatureTaskRuntimeHandoffProjectionBudget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
  var declaredFieldNames: List<String> =
    listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
  var checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  var required: Boolean = true,
  var allowsPrivateArtifactReference: Boolean = false,
  var inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? = null,
) {
  fun build(): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = sourceRef,
    shape = PhaseHandoffProjectionShape(
      projectionName = projectionName,
      projectionContractId = projectionContractId,
      projectionContractVersion = contractVersion,
      promptVisibility = promptVisibility,
      budget = budget,
      declaredFieldNames = declaredFieldNames,
    ),
    delivery = PhaseHandoffProjectionDelivery(
      checkpointPolicy = checkpointPolicy,
      required = required,
      allowsPrivateArtifactReference = allowsPrivateArtifactReference,
      inlineAlternative = inlineAlternative,
      authorizedReferenceKinds = listOfNotNull(inlineAlternative).toSet(),
    ),
  )
}

internal data class HandoffProjectionValidatorInputsFixture(
  var consumerPhaseId: String = HANDOFF_VALIDATOR_TEST_CONSUMER,
  var declarations: List<PhaseHandoffProjectionDeclaration> = listOf(handoffProjectionDeclaration()),
  var resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs = handoffProjectionUpstream(),
  var runInvariants: FeatureTaskRuntimeRunInvariants = handoffProjectionRunInvariants(),
  var resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  var expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  var validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  var qualityGateSelection: FeatureTaskRuntimeQualityGateSelection = VALIDATE,
  var priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
) {
  fun build(): FeatureTaskRuntimeHandoffProjectionInputs = FeatureTaskRuntimeHandoffProjectionInputs(
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
  )
}

internal inline fun handoffProjectionValidatorInputs(
  block: HandoffProjectionValidatorInputsFixture.() -> Unit = {},
): FeatureTaskRuntimeHandoffProjectionInputs = HandoffProjectionValidatorInputsFixture().apply(block).build()

internal fun handoffProjectionValidatorInputs(
  fixture: HandoffProjectionValidatorInputsFixture,
): FeatureTaskRuntimeHandoffProjectionInputs = fixture.build()

internal inline fun handoffProjectionDeclaration(
  block: HandoffProjectionDeclarationFixture.() -> Unit = {},
): PhaseHandoffProjectionDeclaration = HandoffProjectionDeclarationFixture().apply(block).build()

internal fun handoffProjectionDeclaration(
  fixture: HandoffProjectionDeclarationFixture,
): PhaseHandoffProjectionDeclaration = fixture.build()

internal fun handoffProjectionUpstream(
  payload: String = """{"plan":"ok"}""",
): FeatureTaskRuntimeResolvedUpstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(
  mapOf(
    HANDOFF_VALIDATOR_TEST_PRODUCER to FeatureTaskRuntimePhaseOutput(
      phaseId = HANDOFF_VALIDATOR_TEST_PRODUCER,
      iteration = 1,
      payload = payload,
    ),
  ),
)

internal fun handoffProjectionRunInvariants(acceptanceCriteria: List<String> = listOf("AC-1")) =
  FeatureTaskRuntimeRunInvariants(
    specReference = ".feature-specs/SKILL-137/spec.md",
    acceptanceCriteria = acceptanceCriteria,
    mandatesAndOverrides = emptyList(),
  )

internal fun String.quoteHandoffValidatorJson(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal fun valueOnlyFinalizationUpstream(): FeatureTaskRuntimeResolvedUpstreamOutputs {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val planProse = """{"projection_kind":"executable_plan","tasks":[]}"""
  val implementProse = """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"]}"""
  return FeatureTaskRuntimeResolvedUpstreamOutputs(
    mapOf(
      def.PHASE_PLAN to FeatureTaskRuntimePhaseOutput(
        def.PHASE_PLAN,
        1,
        """{"produced_outputs":{"value":${planProse.quoteHandoffValidatorJson()}}}""",
      ),
      def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseOutput(
        def.PHASE_IMPLEMENT,
        1,
        """{"produced_outputs":{"value":${implementProse.quoteHandoffValidatorJson()}}}""",
      ),
      def.PHASE_AUDIT to FeatureTaskRuntimePhaseOutput(
        def.PHASE_AUDIT,
        1,
        """{"verdict":"satisfied","produced_outputs":{}}""",
      ),
      def.PHASE_VALIDATE to FeatureTaskRuntimePhaseOutput(
        def.PHASE_VALIDATE,
        1,
        HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD,
      ),
      def.PHASE_WRITE_HISTORY to FeatureTaskRuntimePhaseOutput(
        def.PHASE_WRITE_HISTORY,
        1,
        HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD,
      ),
      def.PHASE_COMMIT_PUSH to FeatureTaskRuntimePhaseOutput(
        def.PHASE_COMMIT_PUSH,
        1,
        HANDOFF_VALIDATOR_COMMIT_PUSH_PHASE_PAYLOAD,
      ),
    ),
  )
}

internal fun assertValueOnlyConsumerLaunches(
  consumer: String,
  upstream: FeatureTaskRuntimeResolvedUpstreamOutputs,
  checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val gateSelection = if (consumer == def.PHASE_BUILD) {
    BUILD
  } else {
    VALIDATE
  }
  val declarations = if (consumer == def.PHASE_VALIDATE || consumer == def.PHASE_BUILD) {
    FeatureTaskRuntimePhaseWorkflowQueries
      .phaseDeclaration(consumer, FeatureTaskRuntimeFeatureSize.MEDIUM)
      .projectionDeclarations
  } else {
    FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationForQualityGate(
      consumer,
      FeatureTaskRuntimeFeatureSize.MEDIUM,
      gateSelection,
    ).projectionDeclarations
  }
  val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
    handoffProjectionValidatorInputs(
      HandoffProjectionValidatorInputsFixture(
        consumerPhaseId = consumer,
        declarations = declarations,
        resolvedUpstream = upstream,
        resolvedCheckpoint = checkpoint,
      ),
    ),
  )
  assertTrue(envelope.projections.isNotEmpty(), "$consumer must launch with value-only upstream outputs")
}

internal fun assertPrRequestOmitsStuffedImplementFields(
  implement: FeatureTaskRuntimePhaseOutput,
  validate: FeatureTaskRuntimePhaseOutput,
  commitPush: FeatureTaskRuntimePhaseOutput,
  checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val prEnvelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
    handoffProjectionValidatorInputs(
      HandoffProjectionValidatorInputsFixture(
        consumerPhaseId = def.PHASE_PR,
        declarations = FeatureTaskRuntimePhaseWorkflowQueries
          .phaseDeclaration(def.PHASE_PR, FeatureTaskRuntimeFeatureSize.MEDIUM)
          .projectionDeclarations,
        resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
          mapOf(
            def.PHASE_IMPLEMENT to implement,
            def.PHASE_VALIDATE to validate,
            def.PHASE_COMMIT_PUSH to commitPush,
          ),
        ),
        resolvedCheckpoint = checkpoint,
      ),
    ),
  )
  val prRequest = prEnvelope.projections.single { it.projectionName == "pr_request" }
  val stuffedFieldNames = setOf("completed_task_ids", "tests_added", "tests_updated", "deviations")
  assertTrue(prRequest.fields.none { it.name in stuffedFieldNames })
}
