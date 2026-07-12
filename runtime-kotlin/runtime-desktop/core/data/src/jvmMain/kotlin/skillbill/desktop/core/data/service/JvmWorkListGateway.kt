package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.DesktopWorkItem
import skillbill.desktop.core.domain.service.WorkListGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(UserScope::class)
class JvmWorkListGateway(
  private val runtimeServices: DesktopRuntimeApplicationServices = DesktopRuntimeApplicationServices.forCurrentUserHome(),
) : WorkListGateway {
  override fun list(): List<DesktopWorkItem> = runtimeServices.workListService.list().work.map { item ->
    DesktopWorkItem(
      issueKey = item.issueKey,
      workflowKind = item.workflowKind.wireValue,
      workflowId = item.workflowId,
      startedAt = item.startedAt.toString(),
      currentState = item.currentState,
      stateEnteredAt = item.stateEnteredAt.toString(),
      stateEnteredAtEstimated = item.stateEnteredAtEstimated,
    )
  }
}
