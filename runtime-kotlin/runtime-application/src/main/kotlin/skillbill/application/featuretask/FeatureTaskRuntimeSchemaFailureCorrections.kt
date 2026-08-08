package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.MAX_AUDIT_REPAIR_REF_LENGTH

/**
 * The retry corrections derivable from the validator's reason alone.
 *
 * These are separated from [FeatureTaskRuntimePhasePromptComposer] because they need nothing from the
 * briefing: each is a pure function of the rejection text, which is also what makes them directly
 * testable. The corrections that DO need briefing context — the fill-in skeleton, the audit-remediation
 * item list — stay with the composer that owns that context.
 */
internal object FeatureTaskRuntimeSchemaFailureCorrections {
  // The receipt's `reconciled` is `const: true`, so a producer that reports 'completed' while asserting
  // `reconciled: false` is not describing a repairable field error — it is describing work it did not
  // finish, and no edit to that field makes the claim true. This names the envelope that carries
  // unfinished work instead; what happens after that envelope is not this directive's business.
  fun unreconciledReceipt(priorSchemaFailure: String): String {
    val namesReconciled = priorSchemaFailure.contains("reconciliation_evidence.reconciled") ||
      priorSchemaFailure.contains("reconciliation_evidence/reconciled")
    if (!namesReconciled || !priorSchemaFailure.contains("must be the constant value")) {
      return ""
    }
    return """

      A 'completed' implementation_receipt asserts a reconciled working tree: reconciliation_evidence.reconciled
      must be true, and 'completed' is the only status that may carry this receipt. Do not report 'completed'
      with reconciled false, and do not flip the flag to true unless the tree really is at target. If the work
      is genuinely incomplete, leave this phase through a 'blocked' or 'failed' envelope instead.
    """.trimIndent()
  }

  // Every bounded wire field can overflow, not just the two pointer fields. Echoing the validator reason
  // alone is enough for a *missing* field — the reason names it and the agent adds it — but not for an
  // over-length one: the agent already knows which field, and what it does not infer is that it must stop
  // explaining and start compressing. Left uncorrected it re-argues the same case at the same length until
  // the bounded fix loop exhausts, which is exactly how an over-long `reconciliation_evidence.evidence`
  // burned three identical implement attempts while an over-long `artifact_ref` recovered on its next one.
  // So the length branch is decided by the violation, and the field only selects which advice follows.
  fun lengthViolation(priorSchemaFailure: String): String {
    val cap = statedCap(priorSchemaFailure) ?: return ""
    // The two pointer fields are matched by name rather than by position: their advice is about what the
    // field is for, so it holds wherever in the message they are named, including the audit-repair
    // rejections that report them through a bare dotted path with no pointer prefix at all.
    return when {
      priorSchemaFailure.contains("artifact_ref") -> boundedPointerAdvice("artifact_ref", cap)
      priorSchemaFailure.contains("check_ref") -> boundedPointerAdvice("check_ref", cap)
      else -> compressionAdvice(offendingFieldName(priorSchemaFailure), cap)
    }
  }

  // The validator renders caps with digit grouping past a thousand ("4,096"), so the separator is part of
  // the number, not a delimiter after it. Reading the cap from the message rather than from a constant is
  // what lets one branch serve fields bounded at 128, 256, 1024, and 4096 alike. A `maxLength` mention with
  // no readable number still counts as a length violation; the advice then simply omits the figure.
  private fun statedCap(priorSchemaFailure: String): Int? {
    val stated = LENGTH_VIOLATION_PATTERN.find(priorSchemaFailure)?.groupValues?.get(1)
    if (stated != null) {
      return stated.replace(",", "").toIntOrNull() ?: UNSTATED_CAP
    }
    return if (priorSchemaFailure.contains("maxLength")) UNSTATED_CAP else null
  }

  // Mirrors the two pointer shapes `rejectionPath` already parses on the run-loop side — `$.a.b[0].c` and
  // `at '/a/b'` — plus the bare dotted path the domain-side constructors report (`deviations[0].note:`),
  // which carries no prefix to anchor on and so is found by the violation clause that follows it. The last
  // named segment is the offending field; array indices are positions within it, not the field itself.
  private fun offendingFieldName(priorSchemaFailure: String): String? {
    val pointer = DOLLAR_POINTER_PATTERN.find(priorSchemaFailure)?.value?.removePrefix("$")
      ?: SLASH_POINTER_PATTERN.find(priorSchemaFailure)?.groupValues?.get(1)
      ?: BARE_PATH_PATTERN.find(priorSchemaFailure)?.groupValues?.get(1)
      ?: return null
    return pointer.split('.', '/')
      .map { it.substringBefore('[') }
      .lastOrNull { it.isNotBlank() }
  }

  private fun boundedPointerAdvice(field: String, cap: Int): String {
    val replacement = if (field == "artifact_ref") {
      "one repository-relative path, optionally followed by one :symbol, such as " +
        "runtime-kotlin/runtime-mcp/src/test/kotlin/skillbill/mcp/McpStdioServerTest.kt"
    } else {
      "one acceptance-criterion, finding, test, or check identifier, such as AC-005 or McpStdioServerTest"
    }
    val statedCap = if (cap == UNSTATED_CAP) MAX_AUDIT_REPAIR_REF_LENGTH else cap
    return """

      The rejected $field is a bounded pointer, not an evidence container. Replace it with $replacement.
      It MUST be at most $statedCap characters. Do not concatenate multiple paths,
      symbols, findings, commands, or explanations into this field. Put necessary detail in the issue,
      fix, or other schema-authorized descriptive fields.
    """.trimIndent()
  }

  // Names the one correction the agent will not reach on its own: the retry must be SHORTER, not restated.
  // A field this size is a summary of work, and the strongest signal that it overflowed is a segment that
  // applied no edits and spent the response proving convergence instead.
  private fun compressionAdvice(field: String?, cap: Int): String {
    val subject = field?.let { "The rejected $it" } ?: "The rejected field"
    val limit = if (cap == UNSTATED_CAP) "its declared limit" else "$cap characters"
    return """

      $subject exceeded $limit. It is a bounded SUMMARY, not a verification transcript.
      Your previous attempt was rejected for length alone — its content was not disputed, so restating the
      same case at the same length will be rejected again. Shorten what you already claimed: keep the
      conclusion and one concrete anchor (a checkpoint fingerprint, a count of changed paths, a single
      representative path), and drop per-file walkthroughs, reasoning narration, and quoted output. If a
      segment applied no edits, say that it applied none and why it was already satisfied — the absence of
      work is shorter to report than to prove. Move any remaining detail into the schema-authorized
      descriptive fields for this projection, not into this one.
    """.trimIndent()
  }

  // A length violation whose figure the validator did not state; the advice omits the number rather than
  // inventing one, so no correction ever names a cap the schema does not actually enforce.
  private const val UNSTATED_CAP: Int = -1
  private val LENGTH_VIOLATION_PATTERN =
    Regex("""(?:must be|allows) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)
  private val DOLLAR_POINTER_PATTERN = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""")
  private val SLASH_POINTER_PATTERN = Regex("""at '(/[^\s']*)'""")
  private val BARE_PATH_PATTERN =
    Regex("""([A-Za-z_][A-Za-z0-9_\[\].-]*)\s*:\s*(?:must be|allows) at most""", RegexOption.IGNORE_CASE)
}
