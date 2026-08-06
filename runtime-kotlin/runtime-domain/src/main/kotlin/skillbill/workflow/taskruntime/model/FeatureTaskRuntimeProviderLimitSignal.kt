package skillbill.workflow.taskruntime.model

/**
 * A provider usage- or rate-limit refusal recognized in a failed agent launch's own output.
 *
 * [evidence] is the bounded line the signature matched, kept so an operator reads the provider's own
 * words rather than the runtime's paraphrase. [resetHint] is the provider's reset statement when it
 * made one, and is null when it did not — the runtime never invents a reset time.
 */
data class FeatureTaskRuntimeProviderLimitSignal(
  val evidence: String,
  val resetHint: String?,
)
