package skillbill.application.featuretask

import skillbill.ports.agentrun.model.AgentRunLaunchFacts

/** The `rule` a process-failure diagnostic is stored under, distinct from every schema-gate rule. */
const val FEATURE_TASK_RUNTIME_PROCESS_FAILURE_RULE: String = "process-failure"

/**
 * What a child wrote before it died, kept so a process failure leaves something to read.
 *
 * A launch that fails the process never reaches the output gate, so none of the schema-rejection
 * plumbing records it: before this, the only surviving trace of a child that died mid-response was
 * the bounded excerpt inlined in the block reason. That was enough to see THAT a child failed and
 * never enough to see why — a run could report `agent exited with non-zero status 1` four times with
 * no durable artifact behind any of them.
 *
 * Both streams are kept and labelled. A child's own diagnosis arrives on whichever stream it happens
 * to use, and attributing the wrong one is how a misleading message gets believed.
 */
internal data class FeatureTaskRuntimeChildOutput(
  val stdout: String,
  val stderr: String,
  val exitStatus: Int?,
  val timedOut: Boolean,
  val interrupted: Boolean,
  val spawnFailed: Boolean,
) {
  /**
   * The stored body. Framed with the streams named and the exit disposition stated, because the two
   * streams interleaved with no marker are unreadable, and because "empty stdout" and "no stdout
   * captured" are different findings that an unframed dump renders identical.
   */
  fun storedBody(): String = buildString {
    appendLine("### child process failure diagnostic")
    appendLine("### exit_status=${exitStatus ?: "none"} timed_out=$timedOut interrupted=$interrupted")
    appendLine("### spawn_failed=$spawnFailed")
    appendLine("### --- stdout (${stdout.length} chars) ---")
    appendLine(stdout.ifEmpty { "<empty>" })
    appendLine("### --- stderr (${stderr.length} chars) ---")
    appendLine(stderr.ifEmpty { "<empty>" })
    appendLine("### end of child process failure diagnostic")
  }

  /** Nothing on either stream is nothing to persist; the block reason already says the child died. */
  val isEmpty: Boolean get() = stdout.isEmpty() && stderr.isEmpty()
}

internal fun featureTaskRuntimeChildOutput(facts: AgentRunLaunchFacts): FeatureTaskRuntimeChildOutput? =
  FeatureTaskRuntimeChildOutput(
    stdout = facts.stdout,
    stderr = facts.stderr,
    exitStatus = facts.exitStatus,
    timedOut = facts.timedOut,
    interrupted = facts.interrupted,
    spawnFailed = facts.spawnFailed,
  ).takeUnless(FeatureTaskRuntimeChildOutput::isEmpty)
