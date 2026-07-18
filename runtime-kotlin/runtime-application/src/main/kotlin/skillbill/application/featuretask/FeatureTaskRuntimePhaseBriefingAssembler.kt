package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Pure, deterministic assembler of the per-phase launch briefing from a resolved handoff. It
 * serializes all three layers unconditionally: run-invariants (every phase), latest-iteration
 * upstream outputs, and declared derived-context keys.
 *
 * The serialized [FeatureTaskRuntimePhaseLaunchBriefing.briefingText] is guaranteed to stay at or
 * under [FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING] for any upstream count and any
 * input size. It first measures the fixed, non-truncatable overhead (framing, run-invariants,
 * headers, derived-context keys, plus one reserved truncation marker per upstream), then splits
 * the remaining budget equally across the upstream bodies, truncating any over-share body. The
 * typed `upstreamOutputsByPhaseId` field keeps the full payloads; only the serialized text is
 * bounded. If the fixed overhead alone exceeds the ceiling the assembler loud-fails rather than
 * silently truncate the governing contract.
 */
object FeatureTaskRuntimePhaseBriefingAssembler {
  /** Byte ceiling for the serialized [FeatureTaskRuntimePhaseLaunchBriefing.briefingText]. */
  const val FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING: Int = 65_536

  /** Marker put in place of a truncated upstream payload tail, followed by the original byte size. */
  const val UPSTREAM_PAYLOAD_TRUNCATION_MARKER: String = "[truncated: feature-task-runtime per-phase briefing budget]"

  private const val MAX_UTF8_BYTES_PER_CHAR: Int = 4

  fun assemble(handoff: FeatureTaskRuntimePhaseHandoff): FeatureTaskRuntimePhaseLaunchBriefing {
    val upstreamOutputs = handoff.upstreamOutputs.outputsByPhaseId
      .entries
      .associate { (producingPhaseId, output) -> producingPhaseId to output.payload }
    val briefingText = serialize(handoff, upstreamOutputs)
    return FeatureTaskRuntimePhaseLaunchBriefing(
      phaseId = handoff.phaseId,
      specReference = handoff.runInvariants.specReference,
      featureSize = handoff.runInvariants.featureSize.name,
      acceptanceCriteria = handoff.runInvariants.acceptanceCriteria,
      mandatesAndOverrides = handoff.runInvariants.mandatesAndOverrides,
      // Keeps the full payloads for durable fidelity; only briefingText is budget-bounded.
      upstreamOutputsByPhaseId = upstreamOutputs,
      derivedContextKeys = handoff.derivedContextKeys,
      briefingText = briefingText,
      drivingVerdict = handoff.drivingVerdict?.wireValue,
    )
  }

  private fun serialize(handoff: FeatureTaskRuntimePhaseHandoff, upstreamOutputs: Map<String, String>): String {
    // Fixed overhead = the briefing with empty bodies plus one reserved truncation marker per
    // upstream, so the budget holds even if every body ends up truncated.
    val perUpstreamMarkerReservation = upstreamOutputs.values.sumOf { payload ->
      truncationMarkerFor(payload.toByteArray(StandardCharsets.UTF_8).size)
        .toByteArray(StandardCharsets.UTF_8).size
    }
    val overheadBytes = renderBriefing(handoff, upstreamOutputs.mapValues { "" })
      .toByteArray(StandardCharsets.UTF_8).size + perUpstreamMarkerReservation

    val availableForBodies = FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING - overheadBytes
    require(availableForBodies >= 0) {
      "Feature-task-runtime per-phase briefing layer-1/framing overhead is $overheadBytes bytes, exceeding the " +
        "$FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING-byte ceiling before any upstream payload body is " +
        "inlined; the governing contract (spec reference, acceptance criteria, mandates) is too large to fit a " +
        "single phase briefing and must not be silently truncated."
    }

    // Split the remaining budget equally across bodies so the total never exceeds it for any
    // upstream count.
    val upstreamCount = upstreamOutputs.size
    val perBodyBudget = if (upstreamCount == 0) 0 else availableForBodies / upstreamCount

    val boundedBodies = upstreamOutputs.mapValues { (_, payload) -> boundInlinePayload(payload, perBodyBudget) }
    return renderBriefing(handoff, boundedBodies)
  }

  // Called twice: once with empty bodies to measure fixed overhead, once with the bounded bodies.
  @Suppress("LongMethod")
  private fun renderBriefing(handoff: FeatureTaskRuntimePhaseHandoff, upstreamBodies: Map<String, String>): String =
    buildString {
      val invariants = handoff.runInvariants
      appendLine("# Feature-task-runtime phase briefing")
      appendLine("phase: ${handoff.phaseId}")
      handoff.drivingVerdict?.let { verdict -> appendLine("driving_verdict: ${verdict.wireValue}") }
      // Only rendered on an audit_gap re-entry; a forward launch and a review_fix re-entry both carry
      // no gap criteria, so their briefings stay byte-for-byte identical.
      if (handoff.reentryGapCriteria.isNotEmpty()) {
        appendLine("audit_gaps:")
        handoff.reentryGapCriteria.forEach { gap -> appendLine("  - $gap") }
      }
      handoff.auditRepairPlan?.let { plan ->
        appendLine("audit_repair_plan:")
        JsonSupport.mapToJsonString(plan).lineSequence().forEach { appendLine("  $it") }
        appendLine("audit_remediation_execution_rules:")
        appendLine("  - Use the immutable initial preplan and plan; do not regenerate general planning.")
        appendLine("  - Process every runnable carried repair item in dependency order in this invocation.")
        appendLine("  - Emit exactly one terminal repair_item_result for every carried repair_item_id.")
        appendLine("  - already_satisfied requires distinct concrete repository and verification evidence.")
        appendLine("  - Do not defer or assign carried work to review, audit, validation, or a later phase.")
        appendLine(
          "  - If an item is genuinely unresolvable, block with both gap_id and repair_item_id " +
            "and preserve partial terminal evidence.",
        )
      }
      handoff.auditRepairState?.let { repairState ->
        appendLine("audit_repair_state:")
        JsonSupport.mapToJsonString(repairState).lineSequence().forEach { appendLine("  $it") }
      }
      appendLine()
      appendLine("## Run invariants (layer 1, unconditional)")
      appendLine("spec_reference: ${invariants.specReference}")
      appendLine("feature_size: ${invariants.featureSize.name}")
      appendLine("ceremony_scaling:")
      FeatureTaskRuntimePhaseWorkflowDefinition.ceremonyScaling(invariants.featureSize)
        .toBriefingLines()
        .forEach { line -> appendLine("  $line") }
      appendLine("acceptance_criteria:")
      invariants.acceptanceCriteria.forEachIndexed { index, criterion ->
        appendLine("  ${index + 1}. $criterion")
      }
      appendLine("mandates_and_overrides:")
      if (invariants.mandatesAndOverrides.isEmpty()) {
        appendLine("  (none)")
      } else {
        invariants.mandatesAndOverrides.forEach { mandate -> appendLine("  - $mandate") }
      }
      appendLine()
      appendLine("## Upstream outputs (layer 2, latest iteration)")
      if (upstreamBodies.isEmpty()) {
        appendLine("(none)")
      } else {
        upstreamBodies.forEach { (producingPhaseId, body) ->
          appendLine("### from: $producingPhaseId")
          appendLine(body)
        }
      }
      appendLine()
      appendLine("## Derived context (layer 3, declared)")
      if (handoff.derivedContextKeys.isEmpty()) {
        append("(none)")
      } else {
        append(handoff.derivedContextKeys.joinToString(separator = "\n") { key -> "- $key" })
      }
    }

  /**
   * Bounds one inlined upstream body to [budgetBytes] UTF-8 bytes, returning it verbatim when it
   * fits, else a UTF-8-safe prefix plus [UPSTREAM_PAYLOAD_TRUNCATION_MARKER] recording the size.
   */
  private fun boundInlinePayload(payload: String, budgetBytes: Int): String {
    // A string's UTF-8 length is at most 4 bytes/char, so if that worst case already fits we can
    // skip encoding the bytes.
    if (payload.length.toLong() * MAX_UTF8_BYTES_PER_CHAR <= budgetBytes.toLong()) {
      return payload
    }
    val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
    return if (payloadBytes.size <= budgetBytes) {
      payload
    } else {
      utf8SafePrefix(payloadBytes, budgetBytes) + truncationMarkerFor(payloadBytes.size)
    }
  }

  /**
   * Decodes the largest valid UTF-8 prefix of [bytes] within [maxBytes]. Uses a strict REPORTing
   * decoder rather than stripping trailing U+FFFD, so genuine U+FFFD content in the payload is
   * preserved and only partial trailing bytes from a split code point are dropped.
   */
  private fun utf8SafePrefix(bytes: ByteArray, maxBytes: Int): String {
    val limit = maxBytes.coerceAtMost(bytes.size)
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    // A code point is at most 4 bytes, so dropping at most 3 trailing bytes lands on a boundary;
    // an incomplete tail throws under REPORT, so we drop one byte and retry.
    var end = limit
    val minEnd = (limit - (MAX_UTF8_BYTES_PER_CHAR - 1)).coerceAtLeast(0)
    while (end >= minEnd) {
      decoder.reset()
      val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString() }.getOrNull()
      if (decoded != null) {
        return decoded
      }
      end--
    }
    // Unreachable in practice (end == 0 decodes to ""), kept for totality.
    return ""
  }

  private fun truncationMarkerFor(originalBytes: Int): String =
    "$UPSTREAM_PAYLOAD_TRUNCATION_MARKER (original $originalBytes bytes)"
}
