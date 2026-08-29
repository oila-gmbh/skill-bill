package skillbill.application.evidence

import skillbill.application.goalrunner.planning.sha256HexUtf8
import skillbill.application.review.model.ParallelReviewScope
import skillbill.application.review.ReviewCommitRange
import skillbill.application.review.ReviewDiffEvidence
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.nio.file.Path

/**
 * Separates the checkpoint key's segments. NUL cannot occur in a scope name or a revision, so no
 * pair of distinct queries can collide by concatenation.
 */
private const val KEY_SEPARATOR: String = "\u0000"

/** The repository-scoped question one resolve answers, and the whole basis of the checkpoint key. */
internal data class SharedReviewEvidenceQuery(
  val repoRoot: Path,
  val workflowId: String,
  val scope: ParallelReviewScope,
  val range: ReviewCommitRange,
  val suppliedDiff: Boolean,
)

/**
 * Resolves one checkpoint's shared review evidence exactly once, through the checkpoint-keyed store.
 *
 * The stored artifact is a derived cache: a miss, an unreadable payload, or a payload that no longer
 * decodes falls through to in-line derivation. Nothing here can fail a review that would otherwise
 * have run.
 */
internal class SharedReviewEvidenceResolution(
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
) {
  fun resolve(query: SharedReviewEvidenceQuery, resolveAggregateDiff: () -> String): SharedReviewEvidenceRecord {
    val derive = {
      val aggregateDiff = resolveAggregateDiff()
      SharedReviewEvidenceRecord(
        aggregateDiff = aggregateDiff,
        sequence = SharedReviewEvidenceAssembler(diffResolver)
          .assemble(query.scope, query.repoRoot, query.range, query.suppliedDiff),
      )
    }
    val checkpoint = checkpoint(query)
    if (checkpoint == null) {
      return persistAlreadyDerived(query, derive())
    }
    var derived: SharedReviewEvidenceRecord? = null
    val resolution = sharedEvidenceResolver.resolve(
      FeatureTaskRuntimeSharedEvidenceRequest(query.repoRoot, query.workflowId, checkpoint),
    ) {
      derive().also { derived = it }.let(::derivationOf)
    }
    val record = derived ?: SharedReviewEvidenceCodec.decode(resolution.diffPayload) ?: derive()
    return record.copy(storePath = resolution.storePath)
  }

  private fun persistAlreadyDerived(
    query: SharedReviewEvidenceQuery,
    record: SharedReviewEvidenceRecord,
  ): SharedReviewEvidenceRecord {
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = sha256HexUtf8(record.aggregateDiff),
      baseRef = query.range.baseRevision,
      headRef = query.range.headRevision,
    )
    val resolution = sharedEvidenceResolver.resolve(
      FeatureTaskRuntimeSharedEvidenceRequest(query.repoRoot, query.workflowId, checkpoint),
    ) {
      derivationOf(record)
    }
    return record.copy(storePath = resolution.storePath)
  }

  /**
   * The checkpoint the artifact is keyed on. A commit range is fully identified by its immutable
   * base and head, so a hit there needs no repository access at all. A working-tree or supplied-diff
   * review has no such identity; rather than key it on a range that cannot change when the tree does,
   * this returns null and the caller derives in line every time.
   */
  private fun checkpoint(query: SharedReviewEvidenceQuery): FeatureTaskRuntimeRepositoryCheckpoint? {
    val scope = query.scope
    val range = query.range
    if (query.suppliedDiff) return null
    if (scope != ParallelReviewScope.BRANCH && scope != ParallelReviewScope.PR) return null
    val key = listOf(scope.name, range.baseRevision, range.headRevision)
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = sha256HexUtf8(key.joinToString(KEY_SEPARATOR)),
      baseRef = range.baseRevision,
      headRef = range.headRevision,
    )
  }

  /**
   * The port's derivation view of a record. The file and hunk indexes are the artifact's addressable
   * summary; the payload carries the raw facts a consumer rebuilds identities from.
   */
  private fun derivationOf(record: SharedReviewEvidenceRecord): FeatureTaskRuntimeSharedEvidenceDerivation {
    val evidence = runCatching { ReviewDiffEvidence.parse(record.aggregateDiff) }.getOrNull()
    return FeatureTaskRuntimeSharedEvidenceDerivation(
      baseRef = record.sequence.baseRevision,
      headRef = record.sequence.headRevision,
      files = evidence?.files.orEmpty().map {
        FeatureTaskRuntimeSharedEvidenceFileEntry(it.path, changeKind(it.oldPath, it.newPath))
      },
      hunks = evidence?.hunks.orEmpty().map {
        FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path, it.content.lineSequence().first().ifBlank { "@@" })
      },
      diffPayload = SharedReviewEvidenceCodec.encode(record),
    )
  }

  private fun changeKind(oldPath: String?, newPath: String?): String = when {
    oldPath == null -> "added"
    newPath == null -> "deleted"
    oldPath != newPath -> "renamed"
    else -> "modified"
  }
}
