package skillbill.ports.work

import skillbill.ports.work.model.WorkItem

object EmptyWorkListRepository : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> = emptyList()
}
