package skillbill.ports.db

import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path

interface DatabaseSessionFactory {
  fun resolveDbPath(dbOverride: String? = null): Path

  fun databaseExists(dbOverride: String? = null): Boolean

  fun <T> read(dbOverride: String? = null, block: (UnitOfWork) -> T): T

  fun <T> readIfPresent(dbOverride: String? = null, block: (UnitOfWork) -> T): T? =
    if (databaseExists(dbOverride)) read(dbOverride, block) else null

  fun <T> transaction(dbOverride: String? = null, block: (UnitOfWork) -> T): T

  fun <T> selfManagedWrite(dbOverride: String? = null, block: (UnitOfWork) -> T): T
}
