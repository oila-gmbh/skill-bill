package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal sealed interface LaunchResult {
  data class Captured(
    val stdout: String,
    val stdoutBytes: ByteArray,
    val stdoutTruncated: Boolean,
    val stdoutByteSize: Long,
    val stdoutSha256: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : LaunchResult
  data class InfraFailure(
    val reason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
    val disposition: FeatureTaskRuntimeFailureDisposition,
    val neverLaunched: Boolean,
    /**
     * Whatever the child wrote before it died, or null where no child ran to write anything.
     *
     * Carried rather than dropped because a process failure reaches no output gate, so nothing
     * else persists it: the only surviving trace was the bounded excerpt inlined in the block
     * reason, which is far too small to diagnose a child that died mid-response.
     */
    val childOutput: FeatureTaskRuntimeChildOutput? = null,
  ) : LaunchResult
  data class RecordRejected(
    val rejection: RecordRejection,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  ) : LaunchResult

  // Not an InfraFailure with a nicer string: this launch settles the phase as PAUSED rather than
  // BLOCKED, so it must not reach the block seam at all.
  data class ProviderLimited(
    val reason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
  ) : LaunchResult

  val capturedStdout: String? get() = (this as? Captured)?.stdout
  val capturedStdoutBytes: ByteArray? get() = (this as? Captured)?.stdoutBytes
  val capturedStdoutTruncated: Boolean get() = (this as? Captured)?.stdoutTruncated == true
  val capturedStdoutByteSize: Long? get() = (this as? Captured)?.stdoutByteSize
  val capturedStdoutSha256: String? get() = (this as? Captured)?.stdoutSha256
  val infraFailureReason: String? get() = (this as? InfraFailure)?.reason
  val infraFailureChildOutput: FeatureTaskRuntimeChildOutput? get() = (this as? InfraFailure)?.childOutput
  val providerLimitReason: String? get() = (this as? ProviderLimited)?.reason
  val recordRejection: RecordRejection? get() = (this as? RecordRejected)?.rejection

  /**
   * True only where the launch provably never produced a running child: a spawn failure, or a
   * pre-launch capture or declaration rejection. A timeout, an interruption, a non-zero exit and
   * a post-run capture failure all happened around a child that did run under the launched model,
   * so their records must keep it.
   *
   * [RecordRejected] never reaches here: its seam fires before any spawn, so
   * [settleRecordRejection] states never-launched at its own block seams rather than routing the
   * same fact through this getter.
   */
  val childNeverLaunched: Boolean
    get() = (this as? InfraFailure)?.neverLaunched == true
  val failureDisposition: FeatureTaskRuntimeFailureDisposition
    get() = (this as? InfraFailure)?.disposition ?: FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE
  val fileManifest: FeatureTaskRuntimePhaseFileManifest?
  val capturedPhaseOutput: CapturedPhaseOutput? get() = (this as? Captured)?.let(
    CapturedPhaseOutput::fromLaunchCaptured,
  )

  companion object {
    fun captured(captured: CapturedPhaseOutput, fileManifest: FeatureTaskRuntimePhaseFileManifest): LaunchResult =
      Captured(
        captured.text,
        captured.bytes,
        captured.truncated,
        captured.byteSize,
        captured.sha256,
        fileManifest,
      )

    fun captured(args: LaunchCapturedArgs): LaunchResult = captured(
      CapturedPhaseOutput(
        text = args.stdout,
        bytes = args.stdoutBytes,
        truncated = args.stdoutTruncated,
        byteSize = args.stdoutByteSize,
        sha256 = args.stdoutSha256,
      ),
      args.fileManifest,
    )
    fun infraFailure(
      reason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
      childNeverLaunched: Boolean,
      childOutput: FeatureTaskRuntimeChildOutput? = null,
    ): LaunchResult = InfraFailure(
      reason,
      fileManifest,
      FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      childNeverLaunched,
      childOutput,
    )

    /** The provider refused at a usage limit: resumable on its own clock, so the phase pauses. */
    fun providerLimited(reason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest? = null): LaunchResult =
      ProviderLimited(reason, fileManifest)

    /** Static declaration or configuration drift: retrying without operator action reproduces it. */
    fun projectionRejected(reason: String): LaunchResult =
      InfraFailure(reason, null, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, neverLaunched = true)

    /**
     * A durable upstream producer record was rejected at projection validation: the run quarantines
     * it and re-enters the producer under a bounded regeneration cap rather than blocking on first
     * occurrence.
     */
    fun recordRejected(rejectionClass: String, rejectionDetail: String): LaunchResult =
      RecordRejected(RecordRejection(rejectionClass, rejectionDetail))
  }
}
