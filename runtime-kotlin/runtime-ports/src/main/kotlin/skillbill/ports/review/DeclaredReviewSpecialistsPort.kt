package skillbill.ports.review

import java.nio.file.Path

fun interface DeclaredReviewSpecialistsPort {
  fun declaredSpecialists(repoRoot: Path): List<String>

  companion object {
    val NONE = DeclaredReviewSpecialistsPort { emptyList() }
  }
}
