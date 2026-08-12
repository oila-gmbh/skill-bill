@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.featuretask.RejectedOutputDiagnosticRequest
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.requireAccepted
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun completedPhaseBody(
  contractVersion: String,
  phaseId: String,
  summary: String,
  producedOutputs: String,
  verdict: String? = null,
): String {
  val verdictField = verdict?.let { "\"verdict\":\"$it\"," }.orEmpty()
  return "{\"contract_version\":\"$contractVersion\",\"phase_id\":\"$phaseId\",\"status\":\"completed\"," +
    "\"summary\":\"$summary\",$verdictField\"produced_outputs\":$producedOutputs}"
}

/**
 * SKILL-187 subtasks 2–3: gateOutput → private diagnostic → corrective context → next launch, plus
 * path separation, structural-repair identity, truncation/budget metadata, observer isolation, and
 * audit-shaped JSON/YAML conformance at the runner boundary.
 *
 * Realistic bugs these catch while the composer/unit suite still passes: dropped capture metadata,
 * stale prior-attempt bodies on a later phase, missing acceptedAfterStructuralRepair after delimiter
 * repair, a throwing event/status/diagnostic observer aborting or altering the fix-loop outcome, and
 * information loss on nested-verdict / unauthorized-observation / oversized-artifact audit retries.
 */
class FeatureTaskRuntimeCorrectiveRespawnIntegrationTest {
  private val rawSpan = "SKILL187-CORRECTIVE-SENTINEL"
  private val payloadFreeConstraint = "status: does not have a value in the enumeration"

  @Test
  fun `schema-invalid result body and digest match the private diagnostic capture`() {
    // Realistic bug: gateOutput records one capture diagnostically, then rebuilds Exact from a
    // different string/hash so the authorized repair section disagrees with the diagnostic row.
    val rejectedBody = completedPhaseBody("0.2", "review", rawSpan, """{"findings":[]}""")
    var reviewAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "review") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        reviewAttempts += 1
        facts(if (reviewAttempts == 1) rejectedBody else defaultPhaseOutput(request))
      },
      validator = rejectingOnceValidator(rejectedBody),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "review" }
    val retryPrompt = reviewPrompts(harness)[1]
    assertTrue(retryPrompt.contains(rejectedBody), "repair section must carry the captured body")
    assertContains(retryPrompt, "digest=${diagnostic.metadata.sha256}")
    assertContains(retryPrompt, "utf8_bytes=${diagnostic.metadata.byteSize}")
    assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
    assertNoRawResponseSpanOutsideAuthorizedRepairSection(retryPrompt, rejectedBody, rawSpan)
  }

  @Test
  fun `first launch has no repair context and matching retry carries only that attempts body`() {
    val firstBody = completedPhaseBody("0.2", "review", "SKILL187-ATTEMPT-1", """{"findings":[]}""")
    val secondBody = completedPhaseBody("0.2", "review", "SKILL187-ATTEMPT-2", """{"findings":[]}""")
    var reviewAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "review") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        reviewAttempts += 1
        facts(
          when (reviewAttempts) {
            1 -> firstBody
            2 -> secondBody
            else -> defaultPhaseOutput(request)
          },
        )
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "review") return
          if (phaseOutputText.contains("SKILL187-ATTEMPT-1") || phaseOutputText.contains("SKILL187-ATTEMPT-2")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "status: does not have a value in the enumeration — offending value: bad",
              payloadFreeReason = payloadFreeConstraint,
            )
          }
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val prompts = reviewPrompts(harness)
    assertTrue(prompts.size >= 3, "review must reject twice then succeed")
    assertFalse(prompts[0].contains("Untrusted prior phase output"), "first launch must omit repair section")
    assertFalse(prompts[0].contains("REJECTED by the schema gate"), "first launch must omit schema directive")
    assertTrue(prompts[1].contains(firstBody))
    assertFalse(prompts[1].contains("SKILL187-ATTEMPT-2"), "first retry must not carry the later attempt body")
    assertTrue(prompts[2].contains(secondBody))
    assertFalse(prompts[2].contains("SKILL187-ATTEMPT-1"), "second retry must not carry the stale prior body")
  }

  @Test
  fun `a later phase retry cannot receive a stale repair body from an earlier phase`() {
    val reviewBody = completedPhaseBody("0.2", "review", "SKILL187-REVIEW-STALE", """{"findings":[]}""")
    val auditBody = completedPhaseBody("0.3", "audit", "SKILL187-AUDIT-CURRENT", """{"gaps":[]}""", "satisfied")
    var reviewAttempts = 0
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewAttempts += 1
            facts(if (reviewAttempts == 1) reviewBody else defaultPhaseOutput(request))
          }
          "audit" -> {
            auditAttempts += 1
            facts(if (auditAttempts == 1) auditBody else defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel == "review" && phaseOutputText.contains("SKILL187-REVIEW-STALE")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "review rejected",
              payloadFreeReason = payloadFreeConstraint,
            )
          }
          if (sourceLabel == "audit" && phaseOutputText.contains("SKILL187-AUDIT-CURRENT")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "audit rejected",
              payloadFreeReason = "verdict: must be a top-level string",
            )
          }
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val auditRetry = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }[1]
    assertTrue(auditRetry.contains("SKILL187-AUDIT-CURRENT"))
    assertFalse(
      auditRetry.contains("SKILL187-REVIEW-STALE"),
      "audit corrective retry must not inherit the prior review capture",
    )
  }

  @Test
  fun `delimiter repair then schema rejection marks acceptedAfterStructuralRepair without durable raw body`() {
    // Missing closing delimiter + nested verdict: structural repair can close the brace; phase schema
    // still rejects. The next launch must see the capture and the syntax-repair note, not a claim that
    // the phase schema accepted the document.
    val malformed = completedPhaseBody(
      "0.3",
      "audit",
      "SKILL187-DELIMITER",
      """{"gaps":[],"verdict":"satisfied"}""",
    ).dropLast(1)
    val corrected = completedPhaseBody(
      "0.3",
      "audit",
      "criteria met",
      """{"gaps":[],"non_blocking_findings":[]}""",
      "satisfied",
    )
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        facts(if (auditAttempts == 1) malformed else corrected)
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        private val auditValidator = realFeatureTaskRuntimePhaseOutputValidator

        override fun validatePhaseOutput(
          phaseOutputText: String,
          sourceLabel: String,
        ): FeatureTaskRuntimePhaseOutputValidationResult = if (sourceLabel == "audit") {
          auditValidator.validatePhaseOutput(phaseOutputText, sourceLabel)
        } else {
          AlwaysValidValidator.validatePhaseOutput(phaseOutputText, sourceLabel)
        }

        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel)
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val rejected = realFeatureTaskRuntimePhaseOutputValidator.validatePhaseOutput(malformed, "audit")
    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(rejected)
    assertTrue(rejected.structuralRepairEvidence != null, "adapter must retain payload-free repair evidence")

    val auditRetry = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }[1]
    assertContains(auditRetry, "Deterministic syntax repair previously succeeded")
    assertContains(auditRetry, "That does not mean the phase schema accepted it")
    assertTrue(auditRetry.contains(malformed), "post-capture rejected body must reach the repair section")
    val auditRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertFalse(
      requireNotNull(auditRecord.outputArtifact).contains("SKILL187-DELIMITER"),
      "durable accepted artifact must not retain the rejected raw body",
    )
  }

  @Test
  fun `throwing telemetry status and diagnostic observers cannot change outcomes or leak the response`() {
    val rejectedBody = completedPhaseBody("0.2", "review", rawSpan, """{"findings":[]}""")
    val throwingSink = FeatureTaskRuntimeRunEventSink {
      error("status/telemetry observer refused event ${it::class.simpleName}")
    }
    val throwingDiagnostics = object : RuntimeDiagnostics {
      override fun warning(message: String, error: Throwable?) {
        kotlin.error("diagnostic observer refused warning: $message")
      }

      override fun error(message: String, error: Throwable?) {
        kotlin.error("diagnostic observer refused error: $message")
      }
    }

    fun harnessFor(failEveryAttempt: Boolean): RunnerHarness {
      var reviewAttempts = 0
      return runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != "review") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          reviewAttempts += 1
          facts(
            if (failEveryAttempt || reviewAttempts == 1) rejectedBody else defaultPhaseOutput(request),
          )
        },
        validator = object : FeatureTaskRuntimePhaseOutputValidator {
          override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
            if (sourceLabel != "review") return
            if (phaseOutputText.contains(rawSpan)) {
              throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                sourceLabel = sourceLabel,
                reason = "status: does not have a value in the enumeration — offending value: $rawSpan",
                payloadFreeReason = payloadFreeConstraint,
              )
            }
          }
        },
        runtimeConfig = RuntimeHarnessConfig(eventSink = throwingSink),
        diagnostics = throwingDiagnostics,
      )
    }

    val completing = harnessFor(failEveryAttempt = false)
    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(completing.runner.run(completing.request()))
    assertTrue(completed.completedPhaseIds.contains("review"))

    val exhausting = harnessFor(failEveryAttempt = true)
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      exhausting.runner.run(exhausting.request()),
    )
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "exhausted the bounded fix loop")
    assertNoRawResponseSpan(blocked.blockedReason, rawSpan, rejectedBody)
    val reviewRecord = requireNotNull(exhausting.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals(FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT, reviewRecord.failureDisposition)
    assertNoRawResponseSpan(requireNotNull(reviewRecord.blockedReason), rawSpan, rejectedBody)
    assertEquals(
      FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      exhausting.launcher.requests.count {
        phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) == "review"
      },
    )
    // Captured harness events still record phase boundaries without embedding the rejected body.
    exhausting.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseBlocked>().forEach { event ->
      assertNoRawResponseSpan(event.blockedReason, rawSpan, rejectedBody)
    }
    val diagnostic = exhausting.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "review" }
    assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
  }

  @Test
  fun `retryable terminal launches omit the raw repair section at the run loop`() {
    // Composer requires already prove mutual exclusion; this pins the run-loop routing so a
    // schema-valid terminal envelope cannot accidentally carry a prior repair body.
    var reviewLaunches = 0
    val retryableFailure = """
      {
        "contract_version":"0.2",
        "phase_id":"review",
        "status":"failed",
        "failure_disposition":"retryable",
        "summary":"SKILL187-TERMINAL-BLOCK",
        "produced_outputs":{"blocking_reasons":["Temporary input unavailable."]}
      }
    """.trimIndent()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "review") reviewLaunches += 1
        facts(if (phaseId == "review" && reviewLaunches == 1) retryableFailure else defaultPhaseOutput(request))
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val prompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "review" }
    assertTrue(prompts.size >= 2, "retryable terminal must re-enter review")
    val retry = prompts[1]
    assertContains(retry, "reported a retryable block")
    assertOmitsAuthorizedRepairSection(retry)
    assertFalse(retry.contains("REJECTED by the schema gate"))
    assertFalse(retry.contains("Untrusted prior phase output"))
  }

  @Test
  fun `invalid enum and compound artifact_ref rejections carry the captured body into the next launch`() {
    // SKILL-16-style schema failures: unauthorized enum and semicolon-joined artifact_ref. The next
    // launch must see the exact rejected body plus the payload-free constraint, then a corrected
    // envelope must complete the phase.
    val invalidEnum = completedPhaseBody(
      "0.2",
      "review",
      "SKILL187-ENUM",
      """{"findings":[{"severity":"catastrophic"}]}""",
    )
    val compoundRef = completedPhaseBody(
      "0.2",
      "review",
      "SKILL187-ARTIFACT",
      """{"findings":[{"artifact_ref":"a.kt;b.kt;c.kt"}]}""",
    )
    listOf(invalidEnum to "SKILL187-ENUM", compoundRef to "SKILL187-ARTIFACT").forEach { (rejectedBody, sentinel) ->
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
            if (phaseOutputText.contains(sentinel)) {
              throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                sourceLabel = sourceLabel,
                reason = "field rejected — offending value: $sentinel",
                payloadFreeReason = payloadFreeConstraint,
              )
            }
          }
        },
      )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
      val retryPrompt = reviewPrompts(harness)[1]
      assertTrue(retryPrompt.contains(rejectedBody))
      assertRetryPromptNamesConstraint(retryPrompt, "phase-output-schema", payloadFreeConstraint)
      assertNoRawResponseSpanOutsideAuthorizedRepairSection(retryPrompt, rejectedBody, sentinel)
    }
  }

  @Test
  fun `truncated capture keeps digest metadata and omits the exact body from the repair section`() {
    // Realistic bug: truncated stdout is classified Exact from the retained excerpt, so the prompt
    // claims completeness while digest/bytes still describe the full observed stream.
    val excerpt = completedPhaseBody(
      "0.2",
      "review",
      "SKILL187-TRUNCATED-EXCERPT",
      """{"findings":[]}""",
    )
    val fullStreamDigest = "a".repeat(64)
    val fullStreamBytes = 12_345L
    var reviewAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "review") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        reviewAttempts += 1
        if (reviewAttempts == 1) {
          skillbill.ports.agentrun.model.AgentRunLaunchFacts(
            agent = skillbill.install.model.InstallAgent.CLAUDE,
            exitStatus = 0,
            stdout = excerpt,
            stderr = "",
            timedOut = false,
            spawnFailed = false,
            stdoutTruncated = true,
            stdoutByteSize = fullStreamBytes,
            stdoutSha256 = fullStreamDigest,
          )
        } else {
          facts(defaultPhaseOutput(request))
        }
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "review") return
          if (phaseOutputText.contains("SKILL187-TRUNCATED-EXCERPT")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "truncated rejection",
              payloadFreeReason = payloadFreeConstraint,
            )
          }
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retry = reviewPrompts(harness)[1]
    assertContains(retry, "Rejected response body not included in this prompt")
    assertContains(retry, "response_already_truncated")
    assertContains(retry, "digest: $fullStreamDigest")
    assertContains(retry, "utf8_bytes: $fullStreamBytes")
    assertFalse(retry.contains("SKILL187-TRUNCATED-EXCERPT"), "truncated excerpt must not be framed as exact")
  }

  @Test
  fun `a degraded diagnostic leaves no rod token on the authorized repair fallback`() {
    val excerpt = completedPhaseBody(
      "0.2",
      "review",
      "SKILL187-DEGRADED-EXCERPT",
      """{"findings":[]}""",
    )
    val fullStreamDigest = "b".repeat(64)
    val fullStreamBytes = 9_001L
    var reviewAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "review") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        reviewAttempts += 1
        if (reviewAttempts == 1) {
          skillbill.ports.agentrun.model.AgentRunLaunchFacts(
            agent = skillbill.install.model.InstallAgent.CLAUDE,
            exitStatus = 0,
            stdout = excerpt,
            stderr = "",
            timedOut = false,
            spawnFailed = false,
            stdoutTruncated = true,
            stdoutByteSize = fullStreamBytes,
            stdoutSha256 = fullStreamDigest,
          )
        } else {
          facts(defaultPhaseOutput(request))
        }
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "review") return
          if (phaseOutputText.contains("SKILL187-DEGRADED-EXCERPT")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "degraded rejection",
              payloadFreeReason = payloadFreeConstraint,
            )
          }
        }
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordRejectedOutput(
      RejectedOutputDiagnosticRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        attempt = 1,
        rule = "divergent-pre-record",
        path = "/",
        reason = "divergent-pre-record",
        agentId = phaseAgent("review"),
        model = "unspecified",
        rawResponse = "divergent-pre-record".encodeToByteArray(),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retry = reviewPrompts(harness)[1]
    assertContains(retry, "Rejected response body not included in this prompt")
    assertContains(retry, "Private diagnostic write degraded (conflict)")
    assertFalse(retry.contains("rod_"), "degraded write must not fabricate a resolvable locator")
  }

  @Test
  fun `audit nested verdict JSON and flow-YAML share the corrective context contract`() {
    // SKILL-187 AC-002/AC-005: both formats keep the exact capture and a payload-free root-verdict cue.
    val cases = listOf(
      Skill187SyntheticAuditResponses.nestedVerdictComplete() to
        Skill187SyntheticAuditResponses.NESTED_VERDICT_SENTINEL,
      Skill187SyntheticAuditResponses.nestedVerdictConservativeYaml() to
        Skill187SyntheticAuditResponses.YAML_NESTED_SENTINEL,
    )
    cases.forEach { (rejectedBody, sentinel) ->
      var auditAttempts = 0
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditAttempts += 1
          facts(
            if (auditAttempts == 1) rejectedBody else Skill187SyntheticAuditResponses.correctedSatisfied(),
          )
        },
        validator = realAuditValidator(),
      )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

      val prompts = harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .filter { phaseIdFromPrompt(it) == "audit" }
      assertTrue(prompts.size >= 2, "audit must reject then accept")
      assertOmitsAuthorizedRepairSection(prompts[0], sentinel)
      assertMatchingSchemaInvalidRepairPrompt(prompts[1], rejectedBody, "verdict")
      assertTrue(
        prompts[1].contains("top-level") || prompts[1].contains("\"verdict\""),
        "corrective prompt must cue the required root-level verdict shape",
      )
    }
  }

  @Test
  fun `audit unauthorized observation and oversized artifact_ref thread exact captures into corrective launches`() {
    // SKILL-187 AC-003/AC-004: real adapter rejects; next launch sees the body plus payload-free cues.
    listOf(
      Skill187SyntheticAuditResponses.unauthorizedObservation() to
        Skill187SyntheticAuditResponses.OBSERVATION_SENTINEL,
      Skill187SyntheticAuditResponses.oversizedExpandedArtifactRef() to
        Skill187SyntheticAuditResponses.ARTIFACT_SENTINEL,
    ).forEach { (rejectedBody, sentinel) ->
      var auditAttempts = 0
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditAttempts += 1
          facts(
            if (auditAttempts == 1) rejectedBody else Skill187SyntheticAuditResponses.correctedSatisfied(),
          )
        },
        validator = realAuditValidator(),
      )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

      val retry = harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .filter { phaseIdFromPrompt(it) == "audit" }[1]
      assertMatchingSchemaInvalidRepairPrompt(retry, rejectedBody)
      if (sentinel == Skill187SyntheticAuditResponses.OBSERVATION_SENTINEL) {
        assertTrue(retry.contains("observation") || retry.contains("enumeration") || retry.contains("enum"))
        assertNoRawResponseSpanOutsideAuthorizedRepairSection(retry, "blast_radius_inspected")
      } else {
        assertContains(retry, "artifact_ref")
        assertTrue(
          retry.contains("bounded pointer") || retry.contains("at most"),
          "oversized artifact_ref must receive bounded-reference guidance",
        )
      }
      val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
      assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
    }
  }

  @Test
  fun `delimiter then schema rejection keeps digests correlated across the private diagnostic and repair prompt`() {
    // SKILL-187 AC-001/AC-009: original digest on the repair evidence and Exact capture digest stay aligned.
    val malformed = Skill187SyntheticAuditResponses.nestedVerdictMissingDelimiter()
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        facts(
          if (auditAttempts == 1) malformed else Skill187SyntheticAuditResponses.correctedSatisfied(),
        )
      },
      validator = realAuditValidator(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val rejected = realFeatureTaskRuntimePhaseOutputValidator.validatePhaseOutput(malformed, "audit")
    val evidence = requireNotNull(
      assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(rejected).structuralRepairEvidence,
    )
    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
    val retry = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }[1]
    assertEquals(evidence.originalDigest, diagnostic.metadata.sha256)
    assertContains(retry, "digest=${diagnostic.metadata.sha256}")
    assertContains(retry, "Deterministic syntax repair previously succeeded")
    assertMatchingSchemaInvalidRepairPrompt(retry, malformed)
    assertTrue(retry.contains(Skill187SyntheticAuditResponses.NESTED_VERDICT_SENTINEL))
    assertEquals(1, diagnostic.metadata.attempt)
    assertEquals("audit", diagnostic.metadata.phaseId)
  }

  @Test
  fun `exhausted audit correction keeps INVALID_OUTPUT payload-free on every operator surface`() {
    // SKILL-187 AC-010: cap exhaustion never promotes the sentinel into blocked/status/report text.
    val rejectedBody = Skill187SyntheticAuditResponses.nestedVerdictComplete()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        facts(rejectedBody)
      },
      validator = realAuditValidator(),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals("audit", blocked.lastIncompletePhase)
    assertPrivateDiagnosticRejection(
      blocked.blockedReason,
      "phase-output-schema",
      Skill187SyntheticAuditResponses.NESTED_VERDICT_SENTINEL,
      rejectedBody,
    )
    assertNoRawResponseSpan(blocked.blockedReason, rejectedBody)
    val auditRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertNoRawResponseSpan(
      requireNotNull(auditRecord.blockedReason),
      Skill187SyntheticAuditResponses.NESTED_VERDICT_SENTINEL,
      rejectedBody,
    )
    harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }
      .drop(1)
      .forEach { prompt ->
        assertTrue(prompt.contains(rejectedBody) || prompt.contains("Rejected response body not included"))
      }
    val diagnostics = harness.io.database.rejectedDiagnostics().filter { it.metadata.phaseId == "audit" }
    assertTrue(diagnostics.isNotEmpty())
    diagnostics.forEach { row ->
      assertEquals(rejectedBody.encodeToByteArray().toList(), row.payload?.toList())
    }
  }

  private fun reviewPrompts(harness: RunnerHarness): List<String> {
    val prompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "review" }
    assertTrue(prompts.size >= 2, "the review phase must have retried at least once")
    return prompts
  }

  private fun realAuditValidator(): FeatureTaskRuntimePhaseOutputValidator =
    object : FeatureTaskRuntimePhaseOutputValidator {
      private val auditValidator = realFeatureTaskRuntimePhaseOutputValidator

      override fun validatePhaseOutput(
        phaseOutputText: String,
        sourceLabel: String,
      ): FeatureTaskRuntimePhaseOutputValidationResult = if (sourceLabel == "audit") {
        auditValidator.validatePhaseOutput(phaseOutputText, sourceLabel)
      } else {
        AlwaysValidValidator.validatePhaseOutput(phaseOutputText, sourceLabel)
      }

      override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
        validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel)
      }
    }

  private fun rejectingOnceValidator(rejectedBody: String): FeatureTaskRuntimePhaseOutputValidator =
    object : FeatureTaskRuntimePhaseOutputValidator {
      override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
        if (sourceLabel != "review") return
        if (phaseOutputText.contains(rawSpan) || phaseOutputText == rejectedBody) {
          throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            sourceLabel = sourceLabel,
            reason = "status: does not have a value in the enumeration — offending value: $rawSpan",
            payloadFreeReason = payloadFreeConstraint,
          )
        }
      }
    }
}
