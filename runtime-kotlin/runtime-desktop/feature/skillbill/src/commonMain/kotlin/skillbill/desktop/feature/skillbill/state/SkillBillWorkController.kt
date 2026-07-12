package skillbill.desktop.feature.skillbill.state

import kotlinx.coroutines.CancellationException
import skillbill.desktop.core.domain.model.DesktopWorkItem
import skillbill.desktop.core.domain.model.WorkListLoadState
import skillbill.desktop.core.domain.model.WorkListState
import skillbill.desktop.core.domain.service.WorkListGateway

internal data class WorkListRequest(val token: Long)

internal sealed interface WorkListResponse {
  data class Loaded(val items: List<DesktopWorkItem>) : WorkListResponse
  data class Failed(val message: String) : WorkListResponse
}

internal class SkillBillWorkController(
  private val viewState: SkillBillViewState,
  private val gateway: WorkListGateway,
) {
  fun toggle(): WorkListRequest? {
    if (viewState.workList.expanded) {
      viewState.activeWorkListRequestToken += 1
      viewState.workList = viewState.workList.copy(expanded = false, loadState = WorkListLoadState.COLLAPSED)
      viewState.currentState = viewState.createState()
      return null
    }
    return beginLoad()
  }

  fun refresh(): WorkListRequest? = if (canLoad()) beginLoad() else null

  suspend fun load(request: WorkListRequest): WorkListResponse = try {
    WorkListResponse.Loaded(gateway.list())
  } catch (error: CancellationException) {
    throw error
  } catch (error: Throwable) {
    WorkListResponse.Failed(viewState.describe(error))
  }

  fun finish(request: WorkListRequest, response: WorkListResponse) {
    if (request.token != viewState.activeWorkListRequestToken) return
    viewState.workList = when (response) {
      is WorkListResponse.Loaded -> WorkListState(
        expanded = true,
        loadState = if (response.items.isEmpty()) WorkListLoadState.EMPTY else WorkListLoadState.POPULATED,
        items = response.items,
      )
      is WorkListResponse.Failed -> WorkListState(
        expanded = true,
        loadState = WorkListLoadState.ERROR,
        errorMessage = response.message,
      )
    }
    viewState.currentState = viewState.createState()
  }

  private fun beginLoad(): WorkListRequest? {
    if (!canLoad()) return null
    val request = WorkListRequest(++viewState.activeWorkListRequestToken)
    viewState.workList = viewState.workList.copy(
      expanded = true,
      loadState = WorkListLoadState.LOADING,
      errorMessage = null,
    )
    viewState.currentState = viewState.createState()
    return request
  }

  private fun canLoad(): Boolean = viewState.busyOperation == null && viewState.firstRunSetup == null
}
