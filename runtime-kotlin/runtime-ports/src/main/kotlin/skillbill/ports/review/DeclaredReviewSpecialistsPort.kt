package skillbill.ports.review

import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

fun interface DeclaredReviewSpecialistsPort {
  /**
   * Specialists a review over [changedFiles] will actually route to. Scoping by the review's own
   * diff evidence is required, not an optimization: a repo that vendors packs it never installed
   * (skill-bill's own tree ships every pack) would otherwise be asked for every pack's specialists
   * and fail preflight on a pack the review never launches.
   */
  fun routedSpecialists(repoRoot: Path, changedFiles: List<ReviewRoutingChangedFile>): List<String>

  companion object {
    val NONE = DeclaredReviewSpecialistsPort { _, _ -> emptyList() }
  }
}

fun interface InstalledReviewCatalogPort {
  fun manifests(): List<PlatformManifest>

  companion object {
    val NONE = InstalledReviewCatalogPort { emptyList() }
  }
}
