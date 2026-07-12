package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import skillbill.application.work.WorkListService
import skillbill.application.work.WorkListItem
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.DesktopWorkItem
import skillbill.desktop.core.domain.service.WorkListGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@SingleIn(UserScope::class)
class JvmWorkListGateway private constructor(
  private val loadWork: () -> List<WorkListItem>,
  private val zoneIdProvider: () -> ZoneId,
) : WorkListGateway {
  @Inject
  constructor(
    runtimeServices: DesktopRuntimeApplicationServices = DesktopRuntimeApplicationServices.forCurrentUserHome(),
  ) : this({ runtimeServices.workListService.list().work }, ZoneId::systemDefault)

  internal constructor(
    workListService: WorkListService,
    zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
  ) : this({ workListService.list().work }, zoneIdProvider)

  override suspend fun list(): List<DesktopWorkItem> = runInterruptible(Dispatchers.IO) {
    val timestampFormatter = desktopTimestampFormatter(zoneIdProvider())
    loadWork().map { item ->
      DesktopWorkItem(
        issueKey = item.issueKey,
        workflowKind = item.workflowKind.wireValue,
        workflowId = item.workflowId,
        startedAt = timestampFormatter.format(item.startedAt),
        currentState = item.currentState,
        stateEnteredAt = timestampFormatter.format(item.stateEnteredAt),
        stateEnteredAtEstimated = item.stateEnteredAtEstimated,
      )
    }
  }
}

private fun desktopTimestampFormatter(zoneId: ZoneId): DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").withZone(zoneId)
