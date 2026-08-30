package skillbill.application.idestatus

import me.tatarka.inject.annotations.Inject
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.AgentActivityStampRepository
import java.time.Clock
import java.time.Instant

@Inject
class AgentActivityStampWriter(
  private val database: DatabaseSessionFactory,
  private val clock: Clock = Clock.systemUTC(),
) {
  fun lazySink(
    resolveWorkflowId: () -> String?,
    parentWorkflowId: String?,
    dbOverride: String?,
  ): AgentRunActivityStampSink = AgentRunActivityStampSink { label ->
    val workflowId = runCatching { resolveWorkflowId() }.getOrNull()?.takeIf(String::isNotBlank)
      ?: return@AgentRunActivityStampSink
    record(
      StampContext(
        workflowId = workflowId,
        parentWorkflowId = parentWorkflowId?.takeIf(String::isNotBlank),
        dbOverride = dbOverride,
      ),
      label,
    )
  }

  fun sink(workflowId: String, parentWorkflowId: String?, dbOverride: String?): AgentRunActivityStampSink {
    val context = StampContext(
      workflowId = workflowId,
      parentWorkflowId = parentWorkflowId?.takeIf(String::isNotBlank),
      dbOverride = dbOverride,
    )
    return AgentRunActivityStampSink { label -> record(context, label) }
  }

  fun recordEvidenceRead(workflowId: String, parentWorkflowId: String?, dbOverride: String?) {
    record(
      StampContext(
        workflowId = workflowId,
        parentWorkflowId = parentWorkflowId?.takeIf(String::isNotBlank),
        dbOverride = dbOverride,
      ),
      AgentActivityLabel.EVIDENCE_READ,
    )
  }

  private fun record(context: StampContext, label: AgentActivityLabel) {
    if (context.workflowId.isBlank()) return
    val now = Instant.now(clock)
    val stampToPersist = synchronized(latestByWorkflow) {
      val latest = latestByWorkflow.getOrPut(context.workflowId) { LatestStamp() }
      val previous = latest.stamp
      if (previous != null && !now.isAfter(previous.recordedAt)) return
      if (previous?.label == label &&
        now.toEpochMilli() - previous.recordedAt.toEpochMilli() < DEBOUNCE_WINDOW_MILLIS
      ) {
        return
      }
      val stamp = AgentActivityStamp(recordedAt = now, label = label)
      latest.stamp = stamp
      val lastPersist = latest.lastPersistNanos
      val nowNanos = System.nanoTime()
      if (label != AgentActivityLabel.EVIDENCE_READ &&
        lastPersist != 0L &&
        nowNanos - lastPersist < DEBOUNCE_WINDOW_NANOS
      ) {
        return
      }
      latest.lastPersistNanos = nowNanos
      stamp
    }
    persist(context, stampToPersist)
  }

  private fun persist(context: StampContext, stamp: AgentActivityStamp) {
    runCatching {
      database.selfManagedWrite(context.dbOverride) { unitOfWork ->
        writeStamp(unitOfWork.agentActivityStamps, context.workflowId, stamp)
        context.parentWorkflowId?.let { parentId ->
          writeStamp(unitOfWork.agentActivityStamps, parentId, stamp)
        }
      }
    }
  }

  private fun writeStamp(repository: AgentActivityStampRepository, workflowId: String, stamp: AgentActivityStamp) {
    repository.record(workflowId, stamp)
  }

  private data class StampContext(
    val workflowId: String,
    val parentWorkflowId: String?,
    val dbOverride: String?,
  )

  private class LatestStamp {
    var stamp: AgentActivityStamp? = null
    var lastPersistNanos: Long = 0L
  }

  private companion object {
    const val DEBOUNCE_WINDOW_MILLIS: Long = 250L
    const val DEBOUNCE_WINDOW_NANOS: Long = DEBOUNCE_WINDOW_MILLIS * 1_000_000L
    val latestByWorkflow = HashMap<String, LatestStamp>()
  }
}
