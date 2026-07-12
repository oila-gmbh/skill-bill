package skillbill.ports.persistence

import skillbill.ports.persistence.model.WorkItem

/** Read-only inventory of durable feature work across workflow families. */
interface WorkListRepository {
  fun list(limit: Int? = null): List<WorkItem>
}

object EmptyWorkListRepository : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> = emptyList()
}
