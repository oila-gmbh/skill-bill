package skillbill.ports.repository

import java.nio.file.Path

interface RepositoryEnclosingRootPort {
  fun enclosingRepositoryRoot(start: Path): Path
}
