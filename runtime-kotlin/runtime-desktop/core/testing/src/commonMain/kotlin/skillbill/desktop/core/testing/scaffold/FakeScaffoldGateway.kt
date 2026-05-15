package skillbill.desktop.core.testing.scaffold

import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway

/**
 * In-memory fake mirroring the sibling Fake* gateway pattern used elsewhere under
 * `runtime-desktop/core/testing/`. Records every dry-run/execute call with the originating payload
 * so view-model tests can assert payload parity between modes (AC2) without exercising the real
 * runtime. Per-kind scripted responses let individual tests dictate Preview/Success/Failed
 * outcomes.
 */
class FakeScaffoldGateway(
  initialDryRunResponses: Map<ScaffoldKind, ScaffoldRunResult> = emptyMap(),
  initialExecuteResponses: Map<ScaffoldKind, ScaffoldRunResult> = emptyMap(),
) : RuntimeScaffoldGateway {
  enum class Mode { DRY_RUN, EXECUTE }

  data class RecordedCall(
    val mode: Mode,
    val payload: ScaffoldPayload,
  )

  private val dryRunResponses: MutableMap<ScaffoldKind, ScaffoldRunResult> = initialDryRunResponses.toMutableMap()
  private val executeResponses: MutableMap<ScaffoldKind, ScaffoldRunResult> = initialExecuteResponses.toMutableMap()

  private val _recordedCalls: MutableList<RecordedCall> = mutableListOf()

  val recordedCalls: List<RecordedCall>
    get() = _recordedCalls.toList()

  val recordedPayloads: List<ScaffoldPayload>
    get() = _recordedCalls.map(RecordedCall::payload)

  var dryRunCallCount: Int = 0
    private set

  var executeCallCount: Int = 0
    private set

  var lastDryRunPayload: ScaffoldPayload? = null
    private set

  var lastExecutePayload: ScaffoldPayload? = null
    private set

  /** Default response when no per-kind script is registered. */
  var defaultDryRunResponse: ScaffoldRunResult? = null
  var defaultExecuteResponse: ScaffoldRunResult? = null

  var scriptedCatalog: ScaffoldCatalogSnapshot = ScaffoldCatalogSnapshot.empty

  var catalogCallCount: Int = 0
    private set

  @Suppress("UNUSED_PARAMETER")
  override suspend fun catalogSnapshot(session: RepoSession?): ScaffoldCatalogSnapshot {
    catalogCallCount += 1
    return scriptedCatalog
  }

  fun scriptDryRun(kind: ScaffoldKind, result: ScaffoldRunResult) {
    dryRunResponses[kind] = result
  }

  fun scriptExecute(kind: ScaffoldKind, result: ScaffoldRunResult) {
    executeResponses[kind] = result
  }

  fun clearRecordedCalls() {
    _recordedCalls.clear()
    dryRunCallCount = 0
    executeCallCount = 0
    lastDryRunPayload = null
    lastExecutePayload = null
  }

  override suspend fun dryRun(payload: ScaffoldPayload): ScaffoldRunResult {
    dryRunCallCount += 1
    lastDryRunPayload = payload
    _recordedCalls += RecordedCall(mode = Mode.DRY_RUN, payload = payload)
    return dryRunResponses[payload.kind]
      ?: defaultDryRunResponse
      ?: error("FakeScaffoldGateway has no dry-run response scripted for ${payload.kind}.")
  }

  override suspend fun execute(payload: ScaffoldPayload): ScaffoldRunResult {
    executeCallCount += 1
    lastExecutePayload = payload
    _recordedCalls += RecordedCall(mode = Mode.EXECUTE, payload = payload)
    return executeResponses[payload.kind]
      ?: defaultExecuteResponse
      ?: error("FakeScaffoldGateway has no execute response scripted for ${payload.kind}.")
  }
}
