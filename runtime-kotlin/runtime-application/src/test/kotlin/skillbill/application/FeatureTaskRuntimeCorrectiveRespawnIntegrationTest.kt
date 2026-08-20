@file:Suppress("MaxLineLength")

package skillbill.application

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
    val rejectedBody = completedPhaseBody("0.2", "audit", rawSpan, """{"unmet_criteria":[]}""")
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        facts(if (auditAttempts == 1) rejectedBody else defaultPhaseOutput(request))
      },
      validator = rejectingOnceValidator(rejectedBody),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
    val retryPrompt = schemaRetryPrompts(harness)[1]
    assertTrue(retryPrompt.contains(rejectedBody), "repair section must carry the captured body")
    assertContains(retryPrompt, "digest=${diagnostic.metadata.sha256}")
    assertContains(retryPrompt, "utf8_bytes=${diagnostic.metadata.byteSize}")
    assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
    assertNoRawResponseSpanOutsideAuthorizedRepairSection(retryPrompt, rejectedBody, rawSpan)
  }

  @Test
  fun `first launch has no repair context and matching retry carries only that attempts body`() {
    val firstBody = completedPhaseBody("0.2", "audit", "SKILL187-ATTEMPT-1", """{"unmet_criteria":[]}""")
    val secondBody = completedPhaseBody("0.2", "audit", "SKILL187-ATTEMPT-2", """{"unmet_criteria":[]}""")
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        facts(
          when (auditAttempts) {
            1 -> firstBody
            2 -> secondBody
            else -> defaultPhaseOutput(request)
          },
        )
      },
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "audit") return
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

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(blocked.blockedReason, "cap=2")

    val prompts = schemaRetryPrompts(harness)
    assertEquals(2, prompts.size, "audit must reject twice then block")
    assertFalse(prompts[0].contains("Untrusted prior phase output"), "first launch must omit repair section")
    assertFalse(prompts[0].contains("REJECTED by the schema gate"), "first launch must omit schema directive")
    assertTrue(prompts[1].contains(firstBody))
    assertTrue(prompts[1].contains("last salvage attempt"))
    assertTrue(prompts[1].contains("Expected shape:"))
    assertTrue(prompts[1].contains("if it still fails, the run blocks"))
    assertFalse(prompts[1].contains("SKILL187-ATTEMPT-2"), "first retry must not carry the later attempt body")
  }

  @Test
  fun `a later phase retry cannot receive a stale repair body from an earlier phase`() {
    val planBody = completedPhaseBody("0.2", "plan", "SKILL187-PLAN-STALE", """{"mode":"direct","tasks":[]}""")
    val auditBody = completedPhaseBody("0.3", "audit", "SKILL187-AUDIT-CURRENT", """{"gaps":[]}""", "satisfied")
    var planAttempts = 0
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "plan" -> {
            planAttempts += 1
            facts(if (planAttempts == 1) planBody else defaultPhaseOutput(request))
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
          if (sourceLabel == "plan" && phaseOutputText.contains("SKILL187-PLAN-STALE")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "plan rejected",
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
      auditRetry.contains("SKILL187-PLAN-STALE"),
      "audit corrective retry must not inherit the prior plan capture",
    )
  }

  @Test
  fun `delimiter repair then expected-shape restore accepts nested verdict without a relaunch`() {
    val malformed = completedPhaseBody(
      "0.3",
      "audit",
      "SKILL187-DELIMITER",
      """{"gaps":[],"verdict":"satisfied"}""",
    ).dropLast(1)
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        facts(malformed)
      },
      validator = realAuditValidator(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val accepted = realFeatureTaskRuntimePhaseOutputValidator.validatePhaseOutput(malformed, "audit")
    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(accepted)
    assertEquals("satisfied", repaired.normalizedOutput.envelope["verdict"])
    assertEquals(1, auditAttempts, "shape restore must not relaunch audit")
    val auditRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertEquals("completed", auditRecord.status)
    assertContains(requireNotNull(auditRecord.outputArtifact), "\"verdict\"")
  }

  @Test
  fun `throwing telemetry status and diagnostic observers cannot change outcomes or leak the response`() {
    val rejectedBody = completedPhaseBody("0.2", "audit", rawSpan, """{"unmet_criteria":[]}""")
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

    fun harnessFor(failingPhase: String, failEveryAttempt: Boolean): RunnerHarness {
      var attempts = 0
      return runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != failingPhase) return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          attempts += 1
          facts(
            if (failEveryAttempt || attempts == 1) rejectedBody else defaultPhaseOutput(request),
          )
        },
        validator = object : FeatureTaskRuntimePhaseOutputValidator {
          override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
            if (sourceLabel != failingPhase) return
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

    val completing = harnessFor(failingPhase = "audit", failEveryAttempt = false)
    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(completing.runner.run(completing.request()))
    assertTrue(completed.completedPhaseIds.contains("audit"))

    val exhausting = harnessFor(failingPhase = "write_history", failEveryAttempt = true)
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      exhausting.runner.run(exhausting.request()),
    )
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertNoRawResponseSpan(blocked.blockedReason, rawSpan, rejectedBody)
    val writeHistoryRecord =
      requireNotNull(exhausting.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["write_history"])
    assertEquals(FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT, writeHistoryRecord.failureDisposition)
    assertNoRawResponseSpan(requireNotNull(writeHistoryRecord.blockedReason), rawSpan, rejectedBody)
    assertEquals(
      1,
      exhausting.launcher.requests.count {
        phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) == "write_history"
      },
    )
    exhausting.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseBlocked>().forEach { event ->
      assertNoRawResponseSpan(event.blockedReason, rawSpan, rejectedBody)
    }
    val diagnostic = exhausting.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "write_history" }
    assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
  }

  @Test
  fun `retryable terminal launches omit the raw repair section at the run loop`() {
    // Composer requires already prove mutual exclusion; this pins the run-loop routing so a
    // schema-valid terminal envelope cannot accidentally carry a prior repair body.
    var auditLaunches = 0
    val retryableFailure = """
      {
        "contract_version":"0.2",
        "phase_id":"audit",
        "status":"failed",
        "failure_disposition":"retryable",
        "summary":"SKILL187-TERMINAL-BLOCK",
        "produced_outputs":{"blocking_reasons":["Temporary input unavailable."]}
      }
    """.trimIndent()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") auditLaunches += 1
        facts(if (phaseId == "audit" && auditLaunches == 1) retryableFailure else defaultPhaseOutput(request))
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val prompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }
    assertTrue(prompts.size >= 2, "retryable terminal must re-enter audit")
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
      "audit",
      "SKILL187-ENUM",
      """{"unmet_criteria":[{"severity":"catastrophic"}]}""",
    )
    val compoundRef = completedPhaseBody(
      "0.2",
      "audit",
      "SKILL187-ARTIFACT",
      """{"unmet_criteria":[{"artifact_ref":"a.kt;b.kt;c.kt"}]}""",
    )
    listOf(invalidEnum to "SKILL187-ENUM", compoundRef to "SKILL187-ARTIFACT").forEach { (rejectedBody, sentinel) ->
      var auditAttempts = 0
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditAttempts += 1
          facts(if (auditAttempts == 1) rejectedBody else defaultPhaseOutput(request))
        },
        validator = object : FeatureTaskRuntimePhaseOutputValidator {
          override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
            if (sourceLabel != "audit") return
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
      val retryPrompt = schemaRetryPrompts(harness)[1]
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
      "audit",
      "SKILL187-TRUNCATED-EXCERPT",
      """{"unmet_criteria":[]}""",
    )
    val fullStreamDigest = "a".repeat(64)
    val fullStreamBytes = 12_345L
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        if (auditAttempts == 1) {
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
          if (sourceLabel != "audit") return
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

    val retry = schemaRetryPrompts(harness)[1]
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
      "audit",
      "SKILL187-DEGRADED-EXCERPT",
      """{"unmet_criteria":[]}""",
    )
    val fullStreamDigest = "b".repeat(64)
    val fullStreamBytes = 9_001L
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        if (auditAttempts == 1) {
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
          if (sourceLabel != "audit") return
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
        phaseId = "audit",
        attempt = 1,
        rule = "divergent-pre-record",
        path = "/",
        reason = "divergent-pre-record",
        agentId = phaseAgent("audit"),
        model = "unspecified",
        rawResponse = "divergent-pre-record".encodeToByteArray(),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val retry = schemaRetryPrompts(harness)[1]
    assertContains(retry, "Rejected response body not included in this prompt")
    assertContains(retry, "Private diagnostic write degraded (conflict)")
    assertFalse(retry.contains("rod_"), "degraded write must not fabricate a resolvable locator")
  }

  @Test
  fun `audit nested verdict JSON and flow-YAML are restored to the expected shape without a relaunch`() {
    val cases = listOf(
      Skill187SyntheticAuditResponses.nestedVerdictComplete(),
      Skill187SyntheticAuditResponses.nestedVerdictConservativeYaml(),
    )
    cases.forEach { rejectedBody ->
      var auditAttempts = 0
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditAttempts += 1
          facts(rejectedBody)
        },
        validator = realAuditValidator(),
      )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
      assertEquals(1, auditAttempts, "nested verdict must be restored on the existing capture")
      val accepted = realFeatureTaskRuntimePhaseOutputValidator.validatePhaseOutput(rejectedBody, "audit")
      assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(accepted)
      assertEquals("satisfied", accepted.normalizedOutput.envelope["verdict"])
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
  fun `delimiter plus nested verdict is restored on the existing capture`() {
    val malformed = Skill187SyntheticAuditResponses.nestedVerdictMissingDelimiter()
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditAttempts += 1
        facts(malformed)
      },
      validator = realAuditValidator(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val accepted = realFeatureTaskRuntimePhaseOutputValidator.validatePhaseOutput(malformed, "audit")
    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(accepted)
    assertEquals("satisfied", repaired.normalizedOutput.envelope["verdict"])
    assertEquals(1, auditAttempts)
    assertTrue(harness.io.database.rejectedDiagnostics().none { it.metadata.phaseId == "audit" })
  }

  @Test
  fun `audit schema correction keeps INVALID_OUTPUT payload-free on every operator surface`() {
    val rejectedBody = Skill187SyntheticAuditResponses.unauthorizedObservation()
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
    val retryPrompt = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }[1]
    assertPrivateDiagnosticRejection(
      retryPrompt.substringBefore("Violated constraint:"),
      "phase-output-schema",
      Skill187SyntheticAuditResponses.OBSERVATION_SENTINEL,
    )
    assertNoRawResponseSpanOutsideAuthorizedRepairSection(
      retryPrompt,
      Skill187SyntheticAuditResponses.OBSERVATION_SENTINEL,
    )
    val diagnostics = harness.io.database.rejectedDiagnostics().filter { it.metadata.phaseId == "audit" }
    assertTrue(diagnostics.isNotEmpty())
    diagnostics.forEach { row ->
      assertEquals(rejectedBody.encodeToByteArray().toList(), row.payload?.toList())
    }
  }

  private fun schemaRetryPrompts(harness: RunnerHarness): List<String> {
    val prompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "audit" }
    assertTrue(prompts.size >= 2, "the audit phase must have retried at least once")
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
        if (sourceLabel != "audit") return
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
