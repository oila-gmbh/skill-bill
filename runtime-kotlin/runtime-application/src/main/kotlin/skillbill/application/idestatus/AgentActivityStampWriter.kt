package skillbill.application.idestatus
import me.tatarka.inject.annotations.Inject
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.workflow.engine.model.WorkflowId
import java.time.Clock

@Inject
class AgentActivityStampWriter(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
) {
  fun lazySink(resolveWorkflowId: () -> WorkflowId?, parentWorkflowId: WorkflowId?): AgentRunActivityStampSink =
    AgentRunActivityStampSink { label ->
      val workflowId = runCatching { resolveWorkflowId() }.getOrNull()?.takeIf { it.value.isNotBlank() }
        ?: return@AgentRunActivityStampSink
      record(
        StampContext(
          workflowId = workflowId,
          parentWorkflowId = parentWorkflowId?.takeIf { it.value.isNotBlank() },
        ),
        label,
      )
    }

  fun sink(workflowId: WorkflowId, parentWorkflowId: WorkflowId?): AgentRunActivityStampSink {
    val context = StampContext(
      workflowId = workflowId,
      parentWorkflowId = parentWorkflowId?.takeIf { it.value.isNotBlank() },
    )
    return AgentRunActivityStampSink { label -> record(context, label) }
  }

  fun recordEvidenceRead(workflowId: WorkflowId, parentWorkflowId: WorkflowId?) {
    record(
      StampContext(
        workflowId = workflowId,
        parentWorkflowId = parentWorkflowId?.takeIf { it.value.isNotBlank() },
      ),
      AgentActivityLabel.EVIDENCE_READ,
    )
  }

  private fun record(context: StampContext, label: AgentActivityLabel) {
    if (context.workflowId.value.isBlank()) return
    val now = clock.instant()
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
      database.selfManagedWrite { unitOfWork ->
        writeStamp(unitOfWork.agentActivityStamps, context.workflowId, stamp)
        context.parentWorkflowId?.let { parentId ->
          writeStamp(unitOfWork.agentActivityStamps, parentId, stamp)
        }
      }
    }
  }

  private fun writeStamp(repository: AgentActivityStampRepository, workflowId: WorkflowId, stamp: AgentActivityStamp) {
    repository.record(workflowId, stamp)
  }

  private data class StampContext(
    val workflowId: WorkflowId,
    val parentWorkflowId: WorkflowId?,
  )

  private class LatestStamp {
    var stamp: AgentActivityStamp? = null
    var lastPersistNanos: Long = 0L
  }

  private companion object {
    const val DEBOUNCE_WINDOW_MILLIS: Long = 250L
    const val DEBOUNCE_WINDOW_NANOS: Long = DEBOUNCE_WINDOW_MILLIS * 1_000_000L
    val latestByWorkflow = HashMap<WorkflowId, LatestStamp>()
  }
}
