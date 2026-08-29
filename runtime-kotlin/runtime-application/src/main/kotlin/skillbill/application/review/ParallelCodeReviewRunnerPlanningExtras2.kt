package skillbill.application.review

import skillbill.application.review.model.DiffResolutionException
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelReviewScope
import skillbill.application.review.model.StackDetectionException
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import java.nio.file.Path

internal fun ParallelCodeReviewRunnerPlanning.resolveDiff(
  request: ParallelCodeReviewRequest,
  revisions: Pair<String, String>,
): String {
  if (request.suppliedDiff != null) {
    return request.suppliedDiff
  }
  val (base, head) = revisions
  val diffText = request.suppliedDiffPath?.let { path ->
    diffResolver.readDiff(path, PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES)
      ?: throw DiffResolutionException(
        "--diff-file must name a readable, non-empty regular file no larger than " +
          "$PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES bytes.",
      )
  } ?: when (request.scope) {
    ParallelReviewScope.STAGED -> runDiff(listOf("git", "diff", "--cached"), request.repoRoot)
    ParallelReviewScope.UNSTAGED -> runDiff(listOf("git", "diff"), request.repoRoot)
    ParallelReviewScope.BRANCH -> runDiff(listOf("git", "diff", base, head), request.repoRoot)
    ParallelReviewScope.PR -> diffResolver.runProcess(listOf("git", "diff", base, head), request.repoRoot)
      ?: runDiff(listOf("gh", "pr", "diff"), request.repoRoot)
    ParallelReviewScope.WORKTREE_FROM_BASE -> resolveWorktreeFromBaseDiff(request, base)
  }
  if (diffText.isBlank() && request.scope != ParallelReviewScope.WORKTREE_FROM_BASE) {
    throw DiffResolutionException("Diff is empty for scope '${request.scope.name.lowercase()}'.")
  }
  return diffText
}

internal fun ParallelCodeReviewRunnerPlanning.resolveWorktreeFromBaseDiff(
  request: ParallelCodeReviewRequest,
  base: String,
): String {
  val args = buildList {
    addAll(listOf("git", "diff", "--binary", base))
    if (request.ownedPathspec.isNotEmpty()) {
      add("--")
      addAll(request.ownedPathspec)
    }
  }
  val tracked = diffResolver.runProcess(args, request.repoRoot).orEmpty()
  val excluded = request.baselineUntrackedPolicy.excludedPaths.toSet()
  val untracked = diffResolver.runProcess(
    listOf("git", "ls-files", "-o", "--exclude-standard", "-z"),
    request.repoRoot,
  ).orEmpty()
    .split('\u0000')
    .map(String::trim)
    .filter(String::isNotBlank)
    .filterNot { it in excluded }
    .filter { path ->
      request.ownedPathspec.isEmpty() || request.ownedPathspec.any { owned ->
        path == owned || path.startsWith("$owned/")
      }
    }
  val patches = StringBuilder()
  untracked.forEach { path ->
    val patch = diffResolver.runProcess(
      listOf("git", "diff", "--binary", "--no-index", "/dev/null", path),
      request.repoRoot,
    ).orEmpty()
    if (patch.isNotBlank()) {
      patches.append(patch)
      if (!patches.endsWith("\n")) patches.append('\n')
    }
  }
  return buildString {
    append(tracked)
    if (patches.isNotEmpty()) {
      if (isNotEmpty() && !endsWith("\n")) append('\n')
      append(patches)
    }
  }
}

internal fun ParallelCodeReviewRunnerPlanning.runDiff(args: List<String>, workDir: Path): String =
  diffResolver.runProcess(args, workDir)
    ?: throw DiffResolutionException(
      "Command failed: ${args.joinToString(" ")}",
    )

internal fun ParallelCodeReviewRunnerPlanning.detectStack(
  evidence: ReviewDiffEvidence,
): ParallelCodeReviewStackDetection {
  val manifests = runCatching { installedPackCatalog.manifests() }
    .getOrElse { e ->
      throw StackDetectionException(
        "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the installed platform packs before running parallel review.",
        e,
      )
    }
  if (manifests.isEmpty()) return ParallelCodeReviewStackDetection(emptyList(), emptyList(), emptyMap())

  val routing = ReviewStackRouting.route(
    manifests,
    evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
  )
  val routed = manifests.filter { it.slug in routing.routedSlugs }
  return ParallelCodeReviewStackDetection(routed, manifests, routing.ownedPathsBySlug)
}
