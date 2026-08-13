@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-152 subtask 1 (AC-011). The feature carries validator constraint text into retry prompts, which
 * creates exactly one new way to leak: routing the value-bearing reason where only the payload-free one
 * belongs. These tests pin the split at the run-loop seam — the retry prompt receives the payload-free
 * constraint, the private diagnostic row receives the value-bearing reason, and no operator-facing surface
 * receives either.
 *
 * SKILL-187: when an authorized corrective-repair projection is present, raw response spans may appear
 * only inside that section; [assertNoRawResponseSpan] remains the contract for every other surface.
 * GateOutput-to-launch propagation of the exact capture is covered by the sentinel integration test below.
 *
 * The validator is a stand-in rather than the real schema because the split is a run-loop routing property:
 * the real validator's own dual-variant rendering is proven in
 * `FeatureTaskRuntimePhaseOutputSchemaValidatorTest`.
 */
class FeatureTaskRuntimeRejectionConstraintPrivacyTest {
  private val rawSpan = "smuggled-response-body-fragment"
  private val valueBearingReason = "status: does not have a value in the enumeration — offending value: $rawSpan"
  private val payloadFreeConstraint = "status: does not have a value in the enumeration"

  @Test
  fun `the retry prompt carries the payload-free constraint and no span of the raw response`() {
    val harness = rejectingHarness { sourceLabel ->
      InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = valueBearingReason,
        payloadFreeReason = payloadFreeConstraint,
      )
    }

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retryPrompt = reviewPrompts(harness)[1]
    assertRetryPromptNamesConstraint(retryPrompt, "phase-output-schema", payloadFreeConstraint)
    assertNoRawResponseSpan(retryPrompt, rawSpan, valueBearingReason, "offending value")
  }

  @Test
  fun `the private diagnostic row records the value-bearing reason alongside the raw bytes`() {
    val harness = rejectingHarness { sourceLabel ->
      InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = valueBearingReason,
        payloadFreeReason = payloadFreeConstraint,
      )
    }

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "review" }
    assertContains(diagnostic.metadata.reason, rawSpan)
    assertTrue(diagnostic.payload?.isNotEmpty() == true, "the row must keep the raw response bytes")
  }

  @Test
  fun `a terminal schema-gate block keeps every operator surface free of the constraint and the raw span`() {
    var writeHistoryAttempts = 0
    val harness = runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "write_history") return
          writeHistoryAttempts += 1
          throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            sourceLabel = sourceLabel,
            reason = valueBearingReason,
            payloadFreeReason = payloadFreeConstraint,
          )
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("write_history", blocked.lastIncompletePhase)
    assertPrivateDiagnosticRejection(blocked.blockedReason, "phase-output-schema", rawSpan, payloadFreeConstraint)
    assertNoRawResponseSpan(blocked.blockedReason, rawSpan)
    val writeHistoryRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["write_history"])
    assertNoRawResponseSpan(requireNotNull(writeHistoryRecord.blockedReason), rawSpan, payloadFreeConstraint)
  }

  @Test
  fun `an audit-repair-plan rejection routes its two reasons the same way`() {
    val harness = rejectingHarness { sourceLabel ->
      InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
        sourceLabel = sourceLabel,
        reason = valueBearingReason,
        payloadFreeReason = payloadFreeConstraint,
      )
    }

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retryPrompt = reviewPrompts(harness)[1]
    assertRetryPromptNamesConstraint(retryPrompt, "audit-repair-plan-schema", payloadFreeConstraint)
    assertNoRawResponseSpan(retryPrompt, rawSpan)
    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "review" }
    assertContains(diagnostic.metadata.reason, rawSpan)
  }

  @Test
  fun `a malformed rejection with no payload-free reason falls back instead of substituting the value-bearing one`() {
    val harness = rejectingHarness { sourceLabel ->
      InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = valueBearingReason,
        failureCode = "malformed",
      )
    }

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retryPrompt = reviewPrompts(harness)[1]
    assertContains(retryPrompt, "Rejected output violated 'phase-output-schema'")
    // Null payloadFreeReason is the contract's fallback case: the prompt withholds the constraint entirely
    // rather than reaching for the value-bearing reason.
    assertNoRawResponseSpan(retryPrompt, rawSpan, "Violated constraint: ")
  }

  @Test
  fun `gateOutput rejection threads the captured response into the next launch repair section`() {
    // Realistic bug: helpers/composer tests pass, but gateOutput drops correctiveRepairContext before
    // PriorAttemptCorrection reaches the next launch, so the agent never sees the exact rejected body.
    val rejectedBody =
      "{\"contract_version\":\"0.2\",\"phase_id\":\"review\",\"status\":\"completed\"," +
        "\"summary\":\"SKILL187-GATEOUTPUT-SENTINEL\",\"produced_outputs\":{\"findings\":[]}}"
    var reviewAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "review") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        reviewAttempts += 1
        facts(if (reviewAttempts == 1) rejectedBody else defaultPhaseOutput(request))
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "review") return
          if (phaseOutputText.contains("SKILL187-GATEOUTPUT-SENTINEL")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = valueBearingReason,
              payloadFreeReason = payloadFreeConstraint,
            )
          }
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retryPrompt = reviewPrompts(harness)[1]
    assertRetryPromptNamesConstraint(retryPrompt, "phase-output-schema", payloadFreeConstraint)
    assertMatchingSchemaInvalidRepairPrompt(retryPrompt, rejectedBody, payloadFreeConstraint)
    assertNoRawResponseSpanOutsideAuthorizedRepairSection(retryPrompt, rawSpan, "offending value")
  }

  private fun reviewPrompts(harness: RunnerHarness): List<String> {
    val prompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "review" }
    assertTrue(prompts.size >= 2, "the review phase must have retried at least once")
    return prompts
  }

  private fun rejectingHarness(error: (String) -> Throwable): RunnerHarness {
    var reviewAttempts = 0
    return runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "review") return
          reviewAttempts += 1
          if (reviewAttempts < 2) throw error(sourceLabel)
        }
      },
    )
  }
}
