
package skillbill.application

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerRetryTest {

  @Test
  fun `a real schema failure still receives the schema-correction directive and not the terminal one`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) { copy(priorSchemaFailure = "produced_outputs must be an object.") }

    assertContains(retry, "REJECTED by the schema gate", false, "schema failure keeps its directive")
    assertTrue(!retry.contains("reported a retryable block"), "schema failure must not get the terminal directive")
  }

  @Test
  fun `an operator blocked-phase retry decision is delivered only to its matching phase`() {
    val reason = "Use fresh-process isolation for Codex CLI workers."
    val retry = FeatureTaskRuntimeOperatorBlockRetry(
      phaseId = "implement",
      reason = reason,
      retriedAt = "2026-07-21T16:30:00Z",
    )

    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) { copy(operatorBlockRetry = retry) }

    assertContains(prompt, "Operator-applied blocked-phase retry decision")
    assertContains(prompt, reason)
    assertFailsWith<IllegalArgumentException> {
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit"),
      ) { copy(operatorBlockRetry = retry) }
    }
  }

  @Test
  fun `salvage retry names the expected shape and that a second failure blocks`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("audit"),
    ) { copy(priorSchemaFailure = "verdict: must be a top-level string") }

    assertContains(retry, "last salvage attempt")
    assertContains(retry, "Expected shape:")
    assertContains(retry, "do not redo the phase work")
    assertContains(retry, "if it still fails, the run blocks")
    assertContains(retry, "\"phase_id\": \"audit\"")
    assertContains(retry, "\"verdict\": \"satisfied\"")
  }

  @Test
  fun `an unparseable-root failure appends a phase-correct fill-in skeleton`() {
    val auditRetry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("audit"),
    ) { copy(priorSchemaFailure = "<root> must be an object.") }
    val reviewRetry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("review"),
    ) { copy(priorSchemaFailure = "<root> must be an object.") }

    assertContains(auditRetry, "could NOT parse a single JSON object", false, "audit names the parse failure")
    assertContains(auditRetry, "Markdown table, or a JSON array", false, "audit names the likely mistake")
    assertContains(auditRetry, "<one sentence describing what this phase did>", false, "audit hands back a skeleton")
    assertContains(auditRetry, "\"phase_id\": \"audit\"", false, "skeleton pins the phase id")
    assertContains(auditRetry, "\"verdict\": \"satisfied\"", false, "audit skeleton seeds the audit verdict")
    assertContains(auditRetry, "\"value\":", false, "audit skeleton seeds produced_outputs.value")
    assertContains(auditRetry, "\"gaps\":[]", false, "audit skeleton example inner shape names gaps")
    assertContains(
      auditRetry,
      "\"non_blocking_findings\":[]",
      false,
      "audit skeleton seeds the non-blocking findings key",
    )
    assertContains(reviewRetry, "\"verdict\": \"approved\"", false, "review skeleton seeds the review verdict")
    assertContains(reviewRetry, "\"findings\": []", false, "review skeleton seeds the review signal key")
  }

  @Test
  fun `a malformed-output failure also appends the fill-in skeleton`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("audit"),
    ) { copy(priorSchemaFailure = "Phase output is malformed: unexpected end-of-input") }

    assertContains(retry, "could NOT parse a single JSON object", false, "malformed output triggers the skeleton")
    assertContains(retry, "<one sentence describing what this phase did>", false, "malformed output hands a skeleton")
  }

  @Test
  fun `a field-level violation still carries the expected salvage shape`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("audit"),
    ) { copy(priorSchemaFailure = "summary: must be a non-empty string") }

    assertContains(retry, "Previous attempt was REJECTED by the schema gate", false, "still corrects")
    assertContains(retry, "last salvage attempt", false, "field errors still get one salvage")
    assertContains(retry, "summary: must be a non-empty string", false, "still carries the field reason")
    assertContains(retry, "Expected shape:", false, "salvage always names the expected shape")
    assertContains(retry, "\"phase_id\": \"audit\"", false, "expected shape pins the phase")
    assertTrue(!retry.contains("could NOT parse a single JSON object"), "no parse-failure block for field errors")
  }

  @Test
  fun `an oversized audit value receives compression retry guidance`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("audit"),
    ) {
      copy(priorSchemaFailure = "produced_outputs.value: must be at most 4096 characters long")
    }

    assertContains(retry, "bounded SUMMARY, not a verification transcript")
    assertContains(retry, "rejected for length alone")
  }

  @Test
  fun `an oversized reconciliation evidence field is told to compress rather than restate`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(
        priorSchemaFailure =
        "Projection validation failed: implement#produced_outputs: " +
          "\$.reconciliation_evidence.evidence: must be at most 4096 characters long",
      )
    }

    assertContains(retry, "The rejected evidence exceeded 4096 characters")
    assertContains(retry, "bounded SUMMARY, not a verification transcript")
    assertContains(retry, "rejected for length alone")
    assertContains(retry, "applied no edits")
    assertTrue(
      !retry.contains("bounded pointer, not an evidence container"),
      "the pointer-replacement advice belongs to artifact_ref/check_ref only",
    )
  }

  @Test
  fun `any other over-length field receives the compression guidance naming that field`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(priorSchemaFailure = "\$.deviations[0].note: must be at most 4096 characters long")
    }

    assertContains(retry, "The rejected note exceeded 4096 characters")
    assertContains(retry, "bounded SUMMARY, not a verification transcript")
  }

  @Test
  fun `a non-length field violation adds no compression guidance`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(
        priorSchemaFailure =
        "\$.reconciliation_evidence.evidence: property 'evidence' is not defined in the schema",
      )
    }

    assertTrue(!retry.contains("bounded SUMMARY"), "a missing/undefined property is not a length violation")
    assertTrue(!retry.contains("bounded pointer"), "no pointer advice either")
  }

  @Test
  fun `a length violation whose cap was truncated away adds no guidance and does not crash`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(priorSchemaFailure = "Projection validation failed: \$.reconciliation_evidence.ev… [truncated]")
    }

    assertContains(retry, "Previous attempt was REJECTED by the schema gate", false, "still corrects")
    assertTrue(!retry.contains("bounded SUMMARY"), "no length advice without a stated violation")
  }

  @Test
  fun `a maxLength violation with no readable figure still compresses without naming a cap`() {
    val retry = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(priorSchemaFailure = "\$.unresolved_items[0]: maxLength constraint violated")
    }

    assertContains(retry, "exceeded its declared limit")
    assertTrue(!retry.contains("exceeded -1 characters"), "the sentinel cap never reaches the prompt")
  }

  @Test
  fun `audit remediation names the audit prose it must implement in this invocation`() {
    val auditGapPayload =
      """{\"gaps\":[{\"criterion\":\"AC-004\",\"note\":\"gap four\"},""" +
        """{\"criterion\":\"AC-005\",\"note\":\"gap five\"}],\"non_blocking_findings\":[]}"""
    val auditOutput = """
    {
      "contract_version": "0.6",
      "phase_id": "audit",
      "status": "completed",
      "summary": "Audit found gaps.",
      "verdict": "gaps_found",
      "produced_outputs": {
        "value": "$auditGapPayload"
      }
    }
    """.trimIndent()
    val briefing = promptComposerBriefingFor(
      phaseId = "implement",
      auditGapReentry = true,
      auditOutput = auditOutput,
    )

    val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)

    assertContains(prompt, "AUDIT-GAP REMEDIATION")
    assertContains(prompt, "AC-004")
    assertContains(prompt, "AC-005")
    assertContains(prompt, "implementation_receipt JSON stuffed inside value")
    assertTrue(!prompt.contains("repair_item_results"))
  }

  @Test
  fun `audit_gap implement re-entry renders prior-gap directive but forward implement does not`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 2,
      priorAuditValues = listOf("""{"gaps":[{"criterion":"AC-002","note":"$AUDIT_GAP_MESSAGE"}]}"""),
    )
    val remediation = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement", priorGapMemory = memory, auditGapReentry = true),
    )
    assertContains(remediation, "Prior-gap memory — re-justify recurrence against prior audit prose")
    assertContains(remediation, "AC-002")
    assertContains(remediation, "prior_audit_values")

    val forward = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    )
    assertTrue(!forward.contains("Prior-gap memory — re-justify recurrence against prior audit prose"))
    assertTrue(!forward.contains("prior_gap_memory"))
  }
}
