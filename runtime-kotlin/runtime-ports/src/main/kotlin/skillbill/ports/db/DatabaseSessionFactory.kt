package skillbill.ports.db

import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path

interface DatabaseSessionFactory {
  fun resolveDbPath(): Path

  fun databaseExists(): Boolean

  fun <T> read(block: (UnitOfWork) -> T): T

  fun <T> readIfPresent(block: (UnitOfWork) -> T): T? = if (databaseExists()) read(block) else null

  fun <T> transaction(block: (UnitOfWork) -> T): T

  fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T
}
