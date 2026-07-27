package skillbill.ports.persistence

import skillbill.ports.persistence.model.WorkItem

object EmptyWorkListRepository : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> = emptyList()
}
