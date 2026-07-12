package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import skillbill.application.work.WorkListService
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.DesktopWorkItem
import skillbill.ports.persistence.model.WorkItem
import skillbill.desktop.core.domain.service.WorkListGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@SingleIn(UserScope::class)
class JvmWorkListGateway private constructor(
  private val loadWork: () -> List<WorkItem>,
) : WorkListGateway {
  @Inject
  constructor(
    runtimeServices: DesktopRuntimeApplicationServices = DesktopRuntimeApplicationServices.forCurrentUserHome(),
  ) : this({ runtimeServices.workListService.list().work })

  internal constructor(workListService: WorkListService) : this({ workListService.list().work })

  override suspend fun list(): List<DesktopWorkItem> = runInterruptible(Dispatchers.IO) {
    loadWork().map { item ->
      DesktopWorkItem(
        issueKey = item.issueKey,
        workflowKind = item.workflowKind.wireValue,
        workflowId = item.workflowId,
        startedAt = desktopTimestampFormatter.format(item.startedAt),
        currentState = item.currentState,
        stateEnteredAt = desktopTimestampFormatter.format(item.stateEnteredAt),
        stateEnteredAtEstimated = item.stateEnteredAtEstimated,
      )
    }
  }
}

private val desktopTimestampFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
