package skillbill.ports.review

import java.nio.file.Path

fun interface DeclaredReviewSpecialistsPort {
  /**
   * Specialists a review over [changedPaths] will actually route to. Scoping by the review's own
   * changed paths is required, not an optimization: a repo that vendors packs it never installed
   * (skill-bill's own tree ships every pack) would otherwise be asked for every pack's specialists
   * and fail preflight on a pack the review never launches.
   */
  fun routedSpecialists(repoRoot: Path, changedPaths: List<String>): List<String>

  companion object {
    val NONE = DeclaredReviewSpecialistsPort { _, _ -> emptyList() }
  }
}
