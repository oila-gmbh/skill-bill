package skillbill.model

import java.nio.file.Path

data class RepositoryRoot(val path: Path) {
  init {
    require(path.isAbsolute) { "repository root must be absolute" }
  }
}
